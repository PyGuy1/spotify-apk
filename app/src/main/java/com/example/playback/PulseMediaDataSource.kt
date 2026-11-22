package com.example.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.download.DownloadedSongsRepository
import com.example.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException

@UnstableApi
class PulseMediaDataSource(
  private val context: Context,
  private val streamResolver: StreamResolver,
  private val downloadedRepo: DownloadedSongsRepository,
  private val qualityProvider: () -> String,
  private val okHttpClient: OkHttpClient,
) : BaseDataSource(/* isNetwork = */ true) {

  private val httpFactory: OkHttpDataSource.Factory =
    OkHttpDataSource.Factory(okHttpClient)
      .setUserAgent(
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
      )

  private var activeDelegate: DataSource? = null
  private var activeUrl: String = ""

  override fun open(dataSpec: DataSpec): Long {
    transferInitializing(dataSpec)
    val uri = dataSpec.uri
    if (uri.scheme == "pulse" && uri.host == "track") {
      val trackId = uri.pathSegments.firstOrNull().orEmpty()
      if (trackId.isBlank()) throw IOException("Missing track id in URI: $uri")

      // 1. Prefer verified local downloaded file when present on device
      val localUriStr = downloadedRepo.findPlayableLocalUri(trackId)
      if (!localUriStr.isNullOrBlank()) {
        val localUri = Uri.parse(localUriStr)
        val localSource: DataSource =
          if (localUri.scheme == "file") FileDataSource() else ContentDataSource(context)
        activeDelegate = localSource
        activeUrl = localUriStr
        val len = localSource.open(dataSpec.buildUpon().setUri(localUri).build())
        transferStarted(dataSpec)
        return len
      }

      // 2. Stream via resolved mirror candidates with transparent failover & expiry refresh
      val quality = qualityProvider()
      var resolved = runBlocking(Dispatchers.IO) { streamResolver.resolveStream(trackId, quality) }
      var candidates = resolved?.audioUrls.orEmpty()
      val tried = HashSet<String>()

      for (attemptRound in 0..1) {
        if (attemptRound == 1) {
          streamResolver.invalidate(trackId)
          resolved =
            runBlocking(Dispatchers.IO) {
              streamResolver.resolveStream(trackId, quality, forceRefresh = true)
            }
          candidates = resolved?.audioUrls.orEmpty().filterNot { tried.contains(it) }
        }

        for (candidateUrl in candidates) {
          if (!tried.add(candidateUrl)) continue
          val httpSource = httpFactory.createDataSource()
          try {
            val remoteSpec = dataSpec.buildUpon().setUri(Uri.parse(candidateUrl)).build()
            val len = httpSource.open(remoteSpec)
            activeDelegate = httpSource
            activeUrl = candidateUrl
            streamResolver.reportHostResult(candidateUrl, true)
            transferStarted(dataSpec)
            return len
          } catch (e: HttpDataSource.InvalidResponseCodeException) {
            try {
              httpSource.close()
            } catch (_: Exception) {}
            streamResolver.reportHostResult(candidateUrl, false)
          } catch (e: Exception) {
            try {
              httpSource.close()
            } catch (_: Exception) {}
            streamResolver.reportHostResult(candidateUrl, false)
          }
        }
      }
      throw IOException("Unable to open audio stream for track $trackId")
    }

    // Standard content://, file://, or https:// direct URI
    val delegate: DataSource =
      when (uri.scheme?.lowercase()) {
        "content" -> ContentDataSource(context)
        "file" -> FileDataSource()
        else -> httpFactory.createDataSource()
      }
    activeDelegate = delegate
    activeUrl = uri.toString()
    val len = delegate.open(dataSpec)
    transferStarted(dataSpec)
    return len
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val d = activeDelegate ?: return C.RESULT_END_OF_INPUT
    val bytesRead = d.read(buffer, offset, length)
    if (bytesRead > 0) {
      bytesTransferred(bytesRead)
    }
    return bytesRead
  }

  override fun getUri(): Uri? = activeDelegate?.uri

  override fun getResponseHeaders(): Map<String, List<String>> =
    activeDelegate?.responseHeaders ?: emptyMap()

  override fun close() {
    val d = activeDelegate
    activeDelegate = null
    activeUrl = ""
    if (d != null) {
      try {
        d.close()
      } finally {
        transferEnded()
      }
    }
  }

  class Factory(
    private val context: Context,
    private val streamResolver: StreamResolver,
    private val downloadedRepo: DownloadedSongsRepository,
    private val qualityProvider: () -> String,
    private val okHttpClient: OkHttpClient,
  ) : DataSource.Factory {
    override fun createDataSource(): DataSource =
      PulseMediaDataSource(context, streamResolver, downloadedRepo, qualityProvider, okHttpClient)
  }
}
