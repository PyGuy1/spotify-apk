package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.artwork.ArtworkRepository
import com.example.db.PulseDatabase
import com.example.download.DownloadedSongsRepository
import com.example.download.SongDownloadManager
import com.example.playback.CacheModule
import com.example.playback.PlaybackStateStore
import com.example.stream.NetworkTransportKind
import com.example.stream.OkHttpStreamClient
import com.example.stream.StreamResolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class InMemoryCookieJar : CookieJar {
  private val store = ConcurrentHashMap<String, List<Cookie>>()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    val key = url.host
    val now = System.currentTimeMillis()
    val merged = LinkedHashMap<String, Cookie>()
    store[key]?.forEach { c ->
      if (c.expiresAt > now) merged[c.name] = c
    }
    cookies.forEach { c ->
      if (c.expiresAt > now) merged[c.name] = c else merged.remove(c.name)
    }
    if (merged.isEmpty()) store.remove(key) else store[key] = merged.values.toList()
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val now = System.currentTimeMillis()
    val list = store[url.host] ?: return emptyList()
    val valid = list.filter { it.expiresAt > now && it.matches(url) }
    if (valid.size != list.size) {
      if (valid.isEmpty()) store.remove(url.host) else store[url.host] = valid
    }
    return valid
  }
}

@OptIn(UnstableApi::class)
class PulseRuntime private constructor(appContext: Context) {
  val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .cookieJar(InMemoryCookieJar())
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(25, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .retryOnConnectionFailure(true)
      .build()

  val database: PulseDatabase = PulseDatabase.get(appContext)
  val cacheModule: CacheModule = CacheModule(appContext)
  val streamResolver: StreamResolver =
    StreamResolver(
      httpClient = OkHttpStreamClient(okHttpClient),
      transportProvider = { detectNetworkTransport(appContext) },
    )
  val artworkRepository: ArtworkRepository = ArtworkRepository(appContext, okHttpClient)
  val downloadedRepo: DownloadedSongsRepository =
    DownloadedSongsRepository(appContext, database = database, cacheModule = cacheModule)
  val downloadManager: SongDownloadManager =
    SongDownloadManager(
      context = appContext,
      streamResolver = streamResolver,
      downloadedRepo = downloadedRepo,
      okHttpClient = okHttpClient,
      cacheModule = cacheModule,
    )
  val stateStore: PlaybackStateStore = PlaybackStateStore(appContext)

  init {
    streamResolver.setCustomServer(stateStore.getCustomServer())
    streamResolver.setEngineMode(stateStore.getEngineMode())
    streamResolver.setWifiQuality(stateStore.getWifiQuality())
    streamResolver.setCellularQuality(stateStore.getCellularQuality())
    streamResolver.setSelfHostedServer(stateStore.getSelfHostedConfig())
  }

  companion object {
    @Volatile private var instance: PulseRuntime? = null

    fun get(context: Context): PulseRuntime =
      instance
        ?: synchronized(this) {
          instance ?: PulseRuntime(context.applicationContext).also { instance = it }
        }

    fun detectNetworkTransport(context: Context): NetworkTransportKind {
      return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
          ?: return NetworkTransportKind.WIFI_OR_UNMETERED
        val network = cm.activeNetwork ?: return NetworkTransportKind.WIFI_OR_UNMETERED
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkTransportKind.WIFI_OR_UNMETERED
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
          caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        ) {
          NetworkTransportKind.WIFI_OR_UNMETERED
        } else {
          NetworkTransportKind.CELLULAR_OR_METERED
        }
      } catch (_: Exception) {
        NetworkTransportKind.WIFI_OR_UNMETERED
      }
    }
  }
}
