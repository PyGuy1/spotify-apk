package com.example

import android.content.Context
import com.example.artwork.ArtworkRepository
import com.example.download.DownloadedSongsRepository
import com.example.download.SongDownloadManager
import com.example.playback.PlaybackStateStore
import com.example.stream.OkHttpStreamClient
import com.example.stream.StreamResolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PulseRuntime private constructor(appContext: Context) {
  val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(25, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()

  val streamResolver: StreamResolver = StreamResolver(OkHttpStreamClient(okHttpClient))
  val artworkRepository: ArtworkRepository = ArtworkRepository(appContext, okHttpClient)
  val downloadedRepo: DownloadedSongsRepository = DownloadedSongsRepository(appContext)
  val downloadManager: SongDownloadManager =
    SongDownloadManager(appContext, streamResolver, downloadedRepo, okHttpClient)
  val stateStore: PlaybackStateStore = PlaybackStateStore(appContext)

  init {
    streamResolver.setCustomServer(stateStore.getCustomServer())
    downloadManager.setAudioQuality(stateStore.getAudioQuality())
  }

  companion object {
    @Volatile private var instance: PulseRuntime? = null

    fun get(context: Context): PulseRuntime =
      instance
        ?: synchronized(this) {
          instance ?: PulseRuntime(context.applicationContext).also { instance = it }
        }
  }
}
