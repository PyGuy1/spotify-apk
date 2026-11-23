package com.example.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import com.example.PulseLog
import com.example.download.DownloadedSongsRepository
import com.example.download.SongFilenameUtils
import com.example.model.PulseTrack
import com.example.stream.AudioCandidate
import com.example.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

enum class PulseFailureCategory {
  RESOLVE,
  EXPIRED,
  NETWORK,
  INVALID_PAYLOAD,
  FORMAT,
}

open class PulseSourceException(
  val category: PulseFailureCategory,
  val userMessage: String,
  message: String,
  cause: Throwable? = null,
) : IOException(message, cause)

class PulseHttpStatusException(
  val statusCode: Int,
  val host: String,
  userMessage: String,
  message: String,
) : PulseSourceException(
  category =
    if (statusCode == 401 || statusCode == 403 || statusCode == 410) {
      PulseFailureCategory.EXPIRED
    } else if (statusCode >= 500 || statusCode == 429 || statusCode == 408) {
      PulseFailureCategory.NETWORK
    } else {
      PulseFailureCategory.INVALID_PAYLOAD
    },
  userMessage = userMessage,
  message = message,
)

@UnstableApi
class PulseMediaDataSource(
  private val context: Context,
  private val streamResolver: StreamResolver,
  private val downloadedRepo: DownloadedSongsRepository,
  private val qualityProvider: () -> String?,
  private val okHttpClient: OkHttpClient,
  private val trackMetadataProvider: (String) -> PulseTrack? = { null },
) : BaseDataSource(/* isNetwork = */ true) {

  private var activeHttpStream: ActiveHttpStream? = null
  private var activeLocalSource: DataSource? = null
  private var currentUri: Uri? = null
  private var currentResponseHeaders: Map<String, List<String>> = emptyMap()

  private class ActiveHttpStream(
    val response: Response,
    val stream: InputStream,
    val candidate: AudioCandidate,
  ) {
    fun closeQuietly() {
      try {
        stream.close()
      } catch (_: Exception) {}
      try {
        response.close()
      } catch (_: Exception) {}
    }
  }

  override fun open(dataSpec: DataSpec): Long {
    transferInitializing(dataSpec)
    val uri = dataSpec.uri

    if (uri.scheme == "pulse" && uri.host == "track") {
      val trackId = uri.pathSegments.firstOrNull().orEmpty()
      if (trackId.isBlank()) {
        throw PulseSourceException(
          PulseFailureCategory.RESOLVE,
          "Invalid song identifier.",
          "Missing track id in URI: $uri",
        )
      }

      // 1. Prefer verified local downloaded file when present on device
      val localUriStr = downloadedRepo.findPlayableLocalUri(trackId)
      if (!localUriStr.isNullOrBlank()) {
        try {
          val localUri = Uri.parse(localUriStr)
          val localSource: DataSource =
            if (localUri.scheme == "file") FileDataSource() else ContentDataSource(context)
          val localSpec = dataSpec.buildUpon().setUri(localUri).build()
          val len = localSource.open(localSpec)
          activeLocalSource = localSource
          currentUri = localUri
          currentResponseHeaders = localSource.responseHeaders
          PulseLog.d(TAG, "Opened local file for $trackId (${localUri.scheme})")
          transferStarted(dataSpec)
          return len
        } catch (e: Exception) {
          PulseLog.w(TAG, "Local URI open failed for $trackId, falling back to stream: ${e.message}")
          if (trackId.startsWith("local_")) {
            throw PulseSourceException(
              PulseFailureCategory.FORMAT,
              "Local audio file could not be opened.",
              "Local audio open failed for $trackId: ${e.message}",
              e,
            )
          }
        }
      }

      if (trackId.startsWith("local_")) {
        throw PulseSourceException(
          PulseFailureCategory.RESOLVE,
          "Local audio file is missing or permission expired.",
          "Local file missing for $trackId",
        )
      }

      // 2. Open remote audio stream via unified InnerTubeX/InnerTube/BravePipe/Piped/SelfHosted pipeline
      return openRemoteWithFailover(trackId, dataSpec)
    }

    if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
      val candidate =
        AudioCandidate(
          url = uri.toString(),
          mimeType = "",
          bitrate = 128_000,
          sourceHost = StreamResolver.extractHostKey(uri.toString()),
        )
      val opened = openValidatedHttpCandidate(candidate, dataSpec, trackId = uri.lastPathSegment.orEmpty())
      activeHttpStream = opened.stream
      currentUri = Uri.parse(opened.stream.response.request.url.toString())
      currentResponseHeaders = opened.stream.response.headers.toMultimap()
      transferStarted(dataSpec)
      return opened.length
    }

    val delegate: DataSource =
      if (uri.scheme == "file") FileDataSource() else ContentDataSource(context)
    val len = delegate.open(dataSpec)
    activeLocalSource = delegate
    currentUri = uri
    currentResponseHeaders = delegate.responseHeaders
    transferStarted(dataSpec)
    return len
  }

  private fun openRemoteWithFailover(trackId: String, dataSpec: DataSpec): Long {
    val explicitQuality = qualityProvider()
    val effectiveQuality = streamResolver.networkQualityManager.effectiveQuality(explicitQuality)
    val pinKey = "$trackId|$effectiveQuality|${streamResolver.getEngineMode().wireName}"
    val trackModel = trackMetadataProvider(trackId) ?: PulseTrack(id = trackId, title = trackId)
    val tried = HashSet<String>()
    var lastException: PulseSourceException? = null

    for (attemptRound in 0..1) {
      val candidates = mutableListOf<AudioCandidate>()
      if (attemptRound == 0) {
        pinnedCandidates[pinKey]?.let { candidates.add(it) }
        val resolved =
          runBlocking(Dispatchers.IO) {
            streamResolver.resolveStreamForTrack(trackModel, explicitQuality = explicitQuality)
          }
        if (resolved != null) {
          if (resolved.candidates.isNotEmpty()) {
            candidates.addAll(resolved.candidates)
          } else {
            resolved.audioUrls.forEach { url ->
              candidates.add(
                AudioCandidate(
                  url = url,
                  mimeType = "",
                  bitrate = 128_000,
                  sourceHost = StreamResolver.extractHostKey(url),
                )
              )
            }
          }
        }
      } else {
        PulseLog.w(TAG, "Refreshing stream URLs for $trackId (round 2 after failover)")
        pinnedCandidates.remove(pinKey)
        streamResolver.invalidate(trackId)
        val refreshed =
          runBlocking(Dispatchers.IO) {
            streamResolver.resolveStreamForTrack(
              trackModel,
              explicitQuality = explicitQuality,
              forceRefresh = true,
            )
          }
        if (refreshed != null) {
          if (refreshed.candidates.isNotEmpty()) {
            candidates.addAll(refreshed.candidates)
          } else {
            refreshed.audioUrls.forEach { url ->
              candidates.add(
                AudioCandidate(
                  url = url,
                  mimeType = "",
                  bitrate = 128_000,
                  sourceHost = StreamResolver.extractHostKey(url),
                )
              )
            }
          }
        }
      }

      if (candidates.isEmpty() && attemptRound == 1) {
        throw lastException
          ?: PulseSourceException(
            PulseFailureCategory.RESOLVE,
            "Unable to resolve audio stream.",
            "StreamResolver returned 0 candidates for $trackId",
          )
      }

      for (candidate in candidates) {
        if (!StreamResolver.isValidStreamUrl(candidate.url)) continue
        if (!tried.add(candidate.url)) continue
        try {
          val opened = openValidatedHttpCandidate(candidate, dataSpec, trackId)
          activeHttpStream = opened.stream
          currentUri = Uri.parse(opened.stream.response.request.url.toString())
          currentResponseHeaders = opened.stream.response.headers.toMultimap()
          pinnedCandidates[pinKey] = candidate
          streamResolver.reportCandidateResult(candidate, true)
          transferStarted(dataSpec)
          return opened.length
        } catch (e: DataSourceException) {
          throw e
        } catch (e: PulseSourceException) {
          lastException = e
          if (pinnedCandidates[pinKey]?.url == candidate.url) {
            pinnedCandidates.remove(pinKey)
          }
          streamResolver.reportCandidateResult(candidate, false)
          PulseLog.w(
            TAG,
            "Candidate rejected track=$trackId host=${candidate.sourceHost} engine=${candidate.engineSource} reason=${e.message}",
          )
        }
      }
    }

    throw lastException
      ?: PulseSourceException(
        PulseFailureCategory.RESOLVE,
        "Unable to resolve audio stream.",
        "All audio stream candidates failed for $trackId",
      )
  }

  private data class OpenedStream(val stream: ActiveHttpStream, val length: Long)

  private fun openValidatedHttpCandidate(
    candidate: AudioCandidate,
    dataSpec: DataSpec,
    trackId: String,
  ): OpenedStream {
    return try {
      executeSingleHttpOpen(candidate, dataSpec, trackId, stripProxyHeaders = false)
    } catch (e: PulseHttpStatusException) {
      if (e.statusCode == 403 || e.statusCode == 401) {
        PulseLog.d(
          TAG,
          "Retrying HTTP ${e.statusCode} on ${candidate.sourceHost} with clean CDN headers",
        )
        executeSingleHttpOpen(candidate, dataSpec, trackId, stripProxyHeaders = true)
      } else {
        throw e
      }
    }
  }

  private fun executeSingleHttpOpen(
    candidate: AudioCandidate,
    dataSpec: DataSpec,
    trackId: String,
    stripProxyHeaders: Boolean,
  ): OpenedStream {
    val reqBuilder = Request.Builder().url(candidate.url)

    dataSpec.httpRequestHeaders.forEach { (k, v) ->
      if (!k.equals("User-Agent", ignoreCase = true) &&
        !k.equals("Accept-Encoding", ignoreCase = true)
      ) {
        reqBuilder.header(k, v)
      }
    }

    // Apply candidate-specific headers (e.g. Jellyfin X-Emby-Token / Authorization)
    candidate.headers.forEach { (k, v) ->
      if (v.isNotBlank()) reqBuilder.header(k, v)
    }

    val hostLower = candidate.sourceHost.lowercase()
    val isDirectGoogleCdn = hostLower.contains("googlevideo.com") || hostLower.contains("youtube.com")
    val userAgent =
      if (stripProxyHeaders) {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/133.0.0.0 Safari/537.36"
      } else if (isDirectGoogleCdn) {
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/133.0.0.0 Mobile Safari/537.36"
      } else {
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/133.0.0.0 Mobile Safari/537.36"
      }

    reqBuilder.header("User-Agent", userAgent)
    reqBuilder.header("Accept", "audio/*, video/mp4;q=0.5, */*;q=0.3")
    reqBuilder.header("Accept-Encoding", "identity")

    if (!stripProxyHeaders && !isDirectGoogleCdn && candidate.apiMirrorHost.startsWith("https://")) {
      try {
        val mirrorHost = URI(candidate.apiMirrorHost).host?.lowercase().orEmpty()
        if (mirrorHost.isNotBlank() && mirrorHost == hostLower) {
          reqBuilder.header("Referer", "${candidate.apiMirrorHost.trimEnd('/')}/")
        }
      } catch (_: Exception) {}
    }

    if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
      val start = dataSpec.position.coerceAtLeast(0L)
      val end =
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) (start + dataSpec.length - 1L) else null
      val rangeValue = if (end != null) "bytes=$start-$end" else "bytes=$start-"
      reqBuilder.header("Range", rangeValue)
    }

    val response =
      try {
        okHttpClient.newCall(reqBuilder.build()).execute()
      } catch (e: UnknownHostException) {
        throw PulseSourceException(
          PulseFailureCategory.NETWORK,
          "Network error. Could not reach audio server.",
          "DNS failure for ${candidate.sourceHost}: ${e.message}",
          e,
        )
      } catch (e: SocketTimeoutException) {
        throw PulseSourceException(
          PulseFailureCategory.NETWORK,
          "Network error. Connection timed out.",
          "Timeout for ${candidate.sourceHost}: ${e.message}",
          e,
        )
      } catch (e: ConnectException) {
        throw PulseSourceException(
          PulseFailureCategory.NETWORK,
          "Network error. Connection refused.",
          "Connect failed for ${candidate.sourceHost}: ${e.message}",
          e,
        )
      } catch (e: SSLException) {
        throw PulseSourceException(
          PulseFailureCategory.NETWORK,
          "Secure connection to audio server failed.",
          "TLS failure for ${candidate.sourceHost}: ${e.message}",
          e,
        )
      } catch (e: IOException) {
        throw PulseSourceException(
          PulseFailureCategory.NETWORK,
          "Network error while opening stream.",
          "IO error on ${candidate.sourceHost}: ${e.message}",
          e,
        )
      }

    val code = response.code
    if (code == 416) {
      response.close()
      throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
    }
    if (!response.isSuccessful) {
      response.close()
      val msg =
        when (code) {
          401, 403, 410 -> "Stream expired or access denied."
          429 -> "Streaming server is rate limited."
          else -> "Audio server returned HTTP $code."
        }
      throw PulseHttpStatusException(
        statusCode = code,
        host = candidate.sourceHost,
        userMessage = msg,
        message = "HTTP $code from ${PulseLog.redactUrl(candidate.url)}",
      )
    }

    val contentType = response.header("Content-Type").orEmpty()
    val ctLower = contentType.substringBefore(';').trim().lowercase()
    if (ctLower.contains("text/html") ||
      ctLower.contains("application/xhtml") ||
      ctLower.contains("application/json") ||
      ctLower.contains("text/plain") ||
      ctLower.contains("application/xml") ||
      ctLower.contains("text/xml") ||
      ctLower.contains("mpegurl") ||
      ctLower.contains("dash+xml") ||
      ctLower.startsWith("image/")
    ) {
      response.close()
      throw PulseSourceException(
        PulseFailureCategory.INVALID_PAYLOAD,
        "Audio source returned an unexpected response.",
        "Rejected non-audio Content-Type '$contentType' from ${candidate.sourceHost}",
      )
    }

    val body = response.body
    if (body == null) {
      response.close()
      throw PulseSourceException(
        PulseFailureCategory.INVALID_PAYLOAD,
        "Audio server returned an empty stream.",
        "Null response body from ${candidate.sourceHost}",
      )
    }

    val rawStream: InputStream = body.byteStream()
    var resultStream: InputStream = rawStream

    var skippedPrefix = 0L
    if (dataSpec.position > 0L && code == 200) {
      val contentRange = response.header("Content-Range")
      if (contentRange.isNullOrBlank()) {
        val skipBytes = dataSpec.position
        val skipped = skipExactBytes(rawStream, skipBytes)
        if (!skipped) {
          response.close()
          throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        skippedPrefix = skipBytes
      }
    }

    var detectedFormatLabel = ctLower.ifBlank { "unknown" }
    if (dataSpec.position == 0L) {
      val pushback = PushbackInputStream(rawStream, 64)
      val head = ByteArray(64)
      var readCount = 0
      while (readCount < head.size) {
        val n = pushback.read(head, readCount, head.size - readCount)
        if (n <= 0) break
        readCount += n
      }
      if (readCount <= 0) {
        try {
          pushback.close()
        } catch (_: Exception) {}
        response.close()
        throw PulseSourceException(
          PulseFailureCategory.INVALID_PAYLOAD,
          "Audio stream was empty.",
          "0-byte stream body from ${candidate.sourceHost}",
        )
      }
      val slice = if (readCount == head.size) head else head.copyOf(readCount)
      val formatInfo = SongFilenameUtils.inspectStreamFormat(contentType, slice, candidate.url)
      if (!formatInfo.isValidAudio) {
        try {
          pushback.close()
        } catch (_: Exception) {}
        response.close()
        throw PulseSourceException(
          PulseFailureCategory.INVALID_PAYLOAD,
          "Audio source returned an invalid response.",
          "${formatInfo.rejectionReason} from ${candidate.sourceHost}",
        )
      }
      detectedFormatLabel = "${formatInfo.extension}/${formatInfo.mimeType}"
      pushback.unread(head, 0, readCount)
      resultStream = pushback
    }

    val contentLen = body.contentLength()
    val adjustedContentLen =
      if (contentLen >= 0L && skippedPrefix > 0L) (contentLen - skippedPrefix).coerceAtLeast(0L)
      else contentLen
    val reportedLength =
      when {
        dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
        adjustedContentLen >= 0L -> adjustedContentLen
        else -> C.LENGTH_UNSET.toLong()
      }

    PulseLog.d(
      TAG,
      "Opened stream track=$trackId http=$code host=${candidate.sourceHost} engine=${candidate.engineSource} " +
        "ct='$contentType' detected='$detectedFormatLabel' len=$reportedLength pos=${dataSpec.position}",
    )

    return OpenedStream(
      stream = ActiveHttpStream(response, resultStream, candidate),
      length = reportedLength,
    )
  }

  private fun skipExactBytes(stream: InputStream, targetBytes: Long): Boolean {
    var remaining = targetBytes
    val scratch = ByteArray(8192)
    while (remaining > 0L) {
      val toRead = minOf(scratch.size.toLong(), remaining).toInt()
      val n = stream.read(scratch, 0, toRead)
      if (n <= 0) return false
      remaining -= n
    }
    return true
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    val local = activeLocalSource
    if (local != null) {
      val bytesRead = local.read(buffer, offset, length)
      if (bytesRead > 0) bytesTransferred(bytesRead)
      return bytesRead
    }
    val http = activeHttpStream ?: return C.RESULT_END_OF_INPUT
    val bytesRead =
      try {
        http.stream.read(buffer, offset, length)
      } catch (e: IOException) {
        streamResolver.reportCandidateResult(http.candidate, false)
        throw PulseSourceException(
          PulseFailureCategory.NETWORK,
          "Network connection lost during playback.",
          "Mid-stream read failed on ${http.candidate.sourceHost}: ${e.message}",
          e,
        )
      }
    if (bytesRead == -1) return C.RESULT_END_OF_INPUT
    if (bytesRead > 0) bytesTransferred(bytesRead)
    return bytesRead
  }

  override fun getUri(): Uri? = currentUri

  override fun getResponseHeaders(): Map<String, List<String>> = currentResponseHeaders

  override fun close() {
    val http = activeHttpStream
    val local = activeLocalSource
    activeHttpStream = null
    activeLocalSource = null
    currentUri = null
    currentResponseHeaders = emptyMap()

    var closedAny = false
    if (local != null) {
      closedAny = true
      try {
        local.close()
      } catch (_: Exception) {}
    }
    if (http != null) {
      closedAny = true
      http.closeQuietly()
    }
    if (closedAny) {
      transferEnded()
    }
  }

  class Factory(
    private val context: Context,
    private val streamResolver: StreamResolver,
    private val downloadedRepo: DownloadedSongsRepository,
    private val qualityProvider: () -> String?,
    private val okHttpClient: OkHttpClient,
    private val trackMetadataProvider: (String) -> PulseTrack? = { null },
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource =
      PulseMediaDataSource(
        context = context,
        streamResolver = streamResolver,
        downloadedRepo = downloadedRepo,
        qualityProvider = qualityProvider,
        okHttpClient = okHttpClient,
        trackMetadataProvider = trackMetadataProvider,
      )
  }

  companion object {
    private const val TAG = "PulseMediaDataSource"
    private val pinnedCandidates = ConcurrentHashMap<String, AudioCandidate>()

    fun clearPinnedUrl(trackId: String) {
      pinnedCandidates.keys.filter { it.startsWith("$trackId|") }.forEach {
        pinnedCandidates.remove(it)
      }
    }
  }
}
