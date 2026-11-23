package com.example.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import com.example.PulseLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Dual-cache architecture backed by a single shared Media3 StandaloneDatabaseProvider:
 * 1) playerCache: LRU-bounded cache in cacheDir/player_cache for streamed audio chunks.
 * 2) downloadCache: persistent NoOpCacheEvictor cache in filesDir/download_cache for offline downloads.
 *
 * Playback lookup order: Local file (in PulseMediaDataSource/MediaItem) -> downloadCache -> playerCache -> live upstream.
 * Download pipeline order: downloadCache (read/write) <- playerCache (read-only chunk reuse) <- live upstream.
 */
@UnstableApi
class CacheModule(
  context: Context,
  playerCacheMaxBytes: Long = DEFAULT_PLAYER_CACHE_BYTES,
) {
  private val appContext = context.applicationContext

  val databaseProvider: DatabaseProvider = StandaloneDatabaseProvider(appContext)

  val playerCacheDir = File(appContext.cacheDir, "player_cache").apply { mkdirs() }
  val downloadCacheDir = File(appContext.filesDir, "download_cache").apply { mkdirs() }

  val playerCache: Cache =
    SimpleCache(
      playerCacheDir,
      LeastRecentlyUsedCacheEvictor(playerCacheMaxBytes),
      databaseProvider,
    )

  val downloadCache: Cache =
    SimpleCache(
      downloadCacheDir,
      NoOpCacheEvictor(),
      databaseProvider,
    )

  @Volatile private var media3DownloadManager: DownloadManager? = null

  fun createPlaybackDataSourceFactory(upstreamFactory: DataSource.Factory): DataSource.Factory {
    val playerReadWriteFactory =
      CacheDataSource.Factory()
        .setCache(playerCache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheWriteDataSinkFactory(
          CacheDataSink.Factory()
            .setCache(playerCache)
            .setFragmentSize(CACHE_FRAGMENT_BYTES)
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    return CacheDataSource.Factory()
      .setCache(downloadCache)
      .setUpstreamDataSourceFactory(playerReadWriteFactory)
      .setCacheWriteDataSinkFactory(null) // playback reads from downloadCache without polluting it
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
  }

  fun createDownloadDataSourceFactory(upstreamFactory: DataSource.Factory): DataSource.Factory {
    // Read-only view of playerCache so Media3 DownloadManager reuses already-streamed chunks!
    val playerReadOnlyReuseFactory =
      CacheDataSource.Factory()
        .setCache(playerCache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    return CacheDataSource.Factory()
      .setCache(downloadCache)
      .setUpstreamDataSourceFactory(playerReadOnlyReuseFactory)
      .setCacheWriteDataSinkFactory(
        CacheDataSink.Factory()
          .setCache(downloadCache)
          .setFragmentSize(CACHE_FRAGMENT_BYTES)
      )
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
  }

  fun createDownloadCacheOnlyDataSource(): CacheDataSource =
    CacheDataSource.Factory()
      .setCache(downloadCache)
      .setUpstreamDataSourceFactory(null)
      .setCacheWriteDataSinkFactory(null)
      .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
      .createDataSource()

  fun getOrCreateMedia3DownloadManager(upstreamFactory: DataSource.Factory): DownloadManager {
    media3DownloadManager?.let { return it }
    return synchronized(this) {
      media3DownloadManager
        ?: DownloadManager(
          appContext,
          databaseProvider,
          downloadCache,
          createDownloadDataSourceFactory(upstreamFactory),
          Executors.newFixedThreadPool(3),
        ).apply {
          maxParallelDownloads = 3
        }.also { media3DownloadManager = it }
    }
  }

  fun getCachedBytesCount(cache: Cache, key: String): Long =
    try {
      cache.getCachedBytes(key, 0L, Long.MAX_VALUE)
    } catch (_: Exception) {
      0L
    }

  fun hasDownloadCacheContent(key: String): Boolean =
    try {
      downloadCache.getCachedSpans(key).isNotEmpty()
    } catch (_: Exception) {
      false
    }

  fun hasPlayerCacheContent(key: String): Boolean =
    try {
      playerCache.getCachedSpans(key).isNotEmpty()
    } catch (_: Exception) {
      false
    }

  fun readAllFromDownloadCache(key: String, maxBytes: Long = 128L * 1024L * 1024L): ByteArray? {
    val spans: NavigableSet<CacheSpan> =
      try {
        downloadCache.getCachedSpans(key)
      } catch (_: Exception) {
        return null
      }
    if (spans.isEmpty()) return null
    val ds = createDownloadCacheOnlyDataSource()
    val spec = DataSpec.Builder().setUri(Uri.parse("pulse://track/$key")).setKey(key).build()
    return try {
      val out = ByteArrayOutputStream()
      ds.open(spec)
      val buf = ByteArray(16 * 1024)
      var total = 0L
      while (true) {
        val n = ds.read(buf, 0, buf.size)
        if (n <= 0) break
        total += n
        if (total > maxBytes) break
        out.write(buf, 0, n)
      }
      out.toByteArray()
    } catch (e: Exception) {
      PulseLog.w(TAG, "Failed reading downloadCache for $key: ${e.message}")
      null
    } finally {
      try {
        ds.close()
      } catch (_: Exception) {}
    }
  }

  fun removeFromDownloadCache(key: String) {
    try {
      downloadCache.removeResource(key)
    } catch (e: Exception) {
      PulseLog.d(TAG, "removeResource(downloadCache, $key) skipped: ${e.message}")
    }
  }

  fun removeFromPlayerCache(key: String) {
    try {
      playerCache.removeResource(key)
    } catch (_: Exception) {}
  }

  fun release() {
    try {
      media3DownloadManager?.release()
    } catch (_: Exception) {}
    media3DownloadManager = null
    try {
      playerCache.release()
    } catch (_: Exception) {}
    try {
      downloadCache.release()
    } catch (_: Exception) {}
  }

  companion object {
    private const val TAG = "CacheModule"
    const val DEFAULT_PLAYER_CACHE_BYTES = 512L * 1024L * 1024L
    const val CACHE_FRAGMENT_BYTES = 2L * 1024L * 1024L
  }
}

private typealias NavigableSet<T> = java.util.NavigableSet<T>
