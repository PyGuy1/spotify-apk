package com.example.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.PulseRuntime
import com.example.download.SongDownloadManager
import com.example.model.DownloadProgressItem
import com.example.model.DownloadedSongRecord
import com.example.model.PlaybackEngineMode
import com.example.model.PlaybackSnapshot
import com.example.model.PulseTrack
import com.example.model.SelfHostedServerConfig
import com.example.playback.PulsePlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

class PulseJsBridge(
  private val context: Context,
  private val webViewProvider: () -> WebView?,
  private val ensureServiceStarted: () -> Unit,
  private val onSaveExportFile: (filename: String, mimeType: String, base64Content: String) -> Unit,
  private val onRequestImportLocalAudio: () -> Unit,
) : PulsePlaybackService.Listener, SongDownloadManager.Listener {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val runtime = PulseRuntime.get(context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile var lastKnownMainFrameUrl: String = ""

  fun attach() {
    PulsePlaybackService.addListener(this)
    runtime.downloadManager.addListener(this)
  }

  fun detach() {
    PulsePlaybackService.removeListener(this)
    runtime.downloadManager.removeListener(this)
  }

  private fun assertTrustedOrigin(): Boolean {
    val cachedUrl = lastKnownMainFrameUrl
    if (isTrustedAppUrl(cachedUrl)) return true
    val wv = webViewProvider() ?: return false
    var currentUrl: String? = null
    if (Looper.myLooper() == Looper.getMainLooper()) {
      currentUrl = wv.url
    } else {
      val latch = CountDownLatch(1)
      mainHandler.post {
        currentUrl = wv.url
        latch.countDown()
      }
      try {
        latch.await(1_000, TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {}
    }
    return isTrustedAppUrl(currentUrl)
  }

  @JavascriptInterface
  fun isNativePlayer(): Boolean = assertTrustedOrigin()

  @JavascriptInterface
  fun saveExportFile(filename: String, mimeType: String, base64Content: String) {
    if (!assertTrustedOrigin()) return
    onSaveExportFile(filename, mimeType, base64Content)
  }

  @JavascriptInterface
  fun getPlaybackSnapshot(): String {
    if (!assertTrustedOrigin()) return "{}"
    val live = PulsePlaybackService.getCurrentService()?.getSnapshot()
    if (live != null) return live.toJson().toString()
    val saved = runtime.stateStore.load()
    val fallbackSnap =
      PlaybackSnapshot(
        queue = saved.queue,
        currentIndex = if (saved.queue.isEmpty()) -1 else saved.currentIndex,
        isPlaying = false,
        isBuffering = false,
        playbackEnded = false,
        positionSec = saved.positionMs / 1000.0,
        durationSec = ((saved.queue.getOrNull(saved.currentIndex)?.durationSec ?: 0).toDouble()),
        bufferedPercent = 0,
        shuffleEnabled = saved.shuffleEnabled,
        repeatEnabled = saved.repeatEnabled,
        radioMode = saved.radioMode,
        autoplayEnabled = saved.autoplayEnabled,
        volumePercent = saved.volumePercent,
        sleepRemainingMs = 0L,
        sleepEndOfSong = false,
        audioQuality = saved.audioQuality,
        errorMessage = "",
        engineMode = saved.engineMode.wireName,
        wifiQuality = saved.wifiQuality,
        cellularQuality = saved.cellularQuality,
      )
    return fallbackSnap.toJson().toString()
  }

  @JavascriptInterface
  fun playQueue(tracksJson: String, startIndex: Int, startPosSec: Double, radioMode: Boolean) {
    if (!assertTrustedOrigin()) return
    val tracks = PulseTrack.listFromJsonArray(tracksJson)
    if (tracks.isEmpty()) return
    val startMs = (startPosSec.coerceAtLeast(0.0) * 1000.0).roundToLong()
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { svc ->
      svc.playQueue(tracks, startIndex, startMs, radioMode, playWhenReady = true)
    }
  }

  @JavascriptInterface
  fun pause() {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.pause() }
  }

  @JavascriptInterface
  fun resume() {
    if (!assertTrustedOrigin()) return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.resume() }
  }

  @JavascriptInterface
  fun toggle() {
    if (!assertTrustedOrigin()) return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.toggle() }
  }

  @JavascriptInterface
  fun seekToSec(seconds: Double) {
    if (!assertTrustedOrigin()) return
    val ms = (seconds.coerceAtLeast(0.0) * 1000.0).roundToLong()
    PulsePlaybackService.dispatchOrQueue { it.seekTo(ms) }
  }

  @JavascriptInterface
  fun skipNext() {
    if (!assertTrustedOrigin()) return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.skipNext() }
  }

  @JavascriptInterface
  fun skipPrevious() {
    if (!assertTrustedOrigin()) return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.skipPrevious() }
  }

  @JavascriptInterface
  fun jumpToIndex(index: Int) {
    if (!assertTrustedOrigin()) return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.jumpToIndex(index) }
  }

  @JavascriptInterface
  fun enqueueTrack(trackJson: String, playNext: Boolean) {
    if (!assertTrustedOrigin()) return
    val track =
      try {
        PulseTrack.fromJson(JSONObject(trackJson))
      } catch (_: Exception) {
        null
      } ?: return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.enqueueTrack(track, playNext) }
  }

  @JavascriptInterface
  fun appendTracks(tracksJson: String) {
    if (!assertTrustedOrigin()) return
    val tracks = PulseTrack.listFromJsonArray(tracksJson)
    if (tracks.isEmpty()) return
    PulsePlaybackService.dispatchOrQueue { it.appendTracks(tracks) }
  }

  @JavascriptInterface
  fun removeQueueItem(index: Int) {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.removeQueueItem(index) }
  }

  @JavascriptInterface
  fun clearUpcoming() {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.clearUpcoming() }
  }

  @JavascriptInterface
  fun setShuffle(enabled: Boolean) {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.setShuffle(enabled) }
  }

  @JavascriptInterface
  fun setRepeat(enabled: Boolean) {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.setRepeat(enabled) }
  }

  @JavascriptInterface
  fun setAutoplay(enabled: Boolean) {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.setAutoplay(enabled) }
  }

  @JavascriptInterface
  fun setVolume(percent: Int) {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.setVolume(percent) }
  }

  @JavascriptInterface
  fun setAudioQuality(quality: String) {
    if (!assertTrustedOrigin()) return
    runtime.stateStore.setAudioQuality(quality)
    runtime.stateStore.setWifiQuality(quality)
    runtime.stateStore.setCellularQuality(quality)
    runtime.streamResolver.setWifiQuality(quality)
    runtime.streamResolver.setCellularQuality(quality)
    runtime.streamResolver.clearCache()
    PulsePlaybackService.dispatchOrQueue { it.setAudioQuality(quality) }
  }

  @JavascriptInterface
  fun setWifiAudioQuality(quality: String) {
    if (!assertTrustedOrigin()) return
    runtime.stateStore.setWifiQuality(quality)
    runtime.streamResolver.setWifiQuality(quality)
    runtime.streamResolver.clearCache()
    PulsePlaybackService.dispatchOrQueue { it.setWifiAudioQuality(quality) }
  }

  @JavascriptInterface
  fun setCellularAudioQuality(quality: String) {
    if (!assertTrustedOrigin()) return
    runtime.stateStore.setCellularQuality(quality)
    runtime.streamResolver.setCellularQuality(quality)
    runtime.streamResolver.clearCache()
    PulsePlaybackService.dispatchOrQueue { it.setCellularAudioQuality(quality) }
  }

  @JavascriptInterface
  fun setEngineMode(modeWire: String) {
    if (!assertTrustedOrigin()) return
    val parsed = PlaybackEngineMode.fromWire(modeWire)
    runtime.stateStore.setEngineMode(parsed)
    runtime.streamResolver.setEngineMode(parsed)
    PulsePlaybackService.dispatchOrQueue { it.setEngineMode(parsed.wireName) }
  }

  @JavascriptInterface
  fun getEngineMode(): String {
    if (!assertTrustedOrigin()) return PlaybackEngineMode.AUTO.wireName
    return runtime.streamResolver.getEngineMode().wireName
  }

  @JavascriptInterface
  fun setSelfHostedServer(configJson: String) {
    if (!assertTrustedOrigin()) return
    val cfg = SelfHostedServerConfig.fromJson(configJson)
    runtime.stateStore.setSelfHostedConfig(cfg)
    runtime.streamResolver.setSelfHostedServer(cfg)
    runtime.streamResolver.clearCache()
    PulsePlaybackService.dispatchOrQueue { it.setSelfHostedServer(configJson) }
  }

  @JavascriptInterface
  fun getSelfHostedServer(): String {
    if (!assertTrustedOrigin()) return "{}"
    return runtime.stateStore.getSelfHostedConfig().toJson(includeSecret = false).toString()
  }

  @JavascriptInterface
  fun setCustomServer(serverUrl: String) {
    if (!assertTrustedOrigin()) return
    runtime.stateStore.setCustomServer(serverUrl)
    runtime.streamResolver.setCustomServer(serverUrl)
    runtime.streamResolver.clearCache()
    PulsePlaybackService.dispatchOrQueue { it.setCustomServer(serverUrl) }
  }

  @JavascriptInterface
  fun setDiscoveredMirrors(pipedJson: String, invidiousJson: String) {
    if (!assertTrustedOrigin()) return
    val piped = parseStringList(pipedJson)
    val inv = parseStringList(invidiousJson)
    runtime.streamResolver.setDiscoveredMirrors(piped, inv)
  }

  @JavascriptInterface
  fun seedPreResolvedStream(trackId: String, quality: String, urlsJson: String) {
    if (!assertTrustedOrigin()) return
    val urls = parseStringList(urlsJson)
    runtime.streamResolver.seedCandidateUrls(trackId, quality, urls)
  }

  @JavascriptInterface
  fun retryStream() {
    if (!assertTrustedOrigin()) return
    ensureServiceStarted()
    PulsePlaybackService.dispatchOrQueue { it.retryCurrentStream() }
  }

  @JavascriptInterface
  fun setSleepTimer(minutes: Int, endOfSong: Boolean) {
    if (!assertTrustedOrigin()) return
    PulsePlaybackService.dispatchOrQueue { it.setSleepTimer(minutes, endOfSong) }
  }

  @JavascriptInterface
  fun downloadSong(trackJson: String, candidateUrlsJson: String) {
    if (!assertTrustedOrigin()) return
    val track =
      try {
        PulseTrack.fromJson(JSONObject(trackJson))
      } catch (_: Exception) {
        null
      } ?: return
    val urls = parseStringList(candidateUrlsJson)
    runtime.downloadManager.enqueueDownload(track, urls)
  }

  @JavascriptInterface
  fun cancelDownload(trackId: String) {
    if (!assertTrustedOrigin()) return
    runtime.downloadManager.cancelDownload(trackId)
  }

  @JavascriptInterface
  fun retryDownload(trackId: String) {
    if (!assertTrustedOrigin()) return
    runtime.downloadManager.retryDownload(trackId)
  }

  @JavascriptInterface
  fun deleteDownloadedSong(trackId: String, localUri: String) {
    if (!assertTrustedOrigin()) return
    scope.launch { runtime.downloadManager.deleteDownloadedSong(trackId, localUri) }
  }

  @JavascriptInterface
  fun syncDownloadedLibrary() {
    if (!assertTrustedOrigin()) return
    scope.launch { runtime.downloadManager.syncLibraryAndNotify() }
  }

  @JavascriptInterface
  fun getDownloadedLibrary(): String {
    if (!assertTrustedOrigin()) return "[]"
    val list = runtime.downloadedRepo.getCachedList()
    return JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
  }

  @JavascriptInterface
  fun getDownloadStates(): String {
    if (!assertTrustedOrigin()) return "[]"
    val list = runtime.downloadManager.getAllDownloadStates()
    return JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
  }

  @JavascriptInterface
  fun importLocalAudio() {
    if (!assertTrustedOrigin()) return
    mainHandler.post { onRequestImportLocalAudio() }
  }

  override fun onPlaybackSnapshot(snapshot: PlaybackSnapshot) {
    emitJsEvent("playback", snapshot.toJson())
  }

  override fun onDownloadUpdated(item: DownloadProgressItem) {
    emitJsEvent("download", item.toJson())
  }

  override fun onLibrarySynced(songs: List<DownloadedSongRecord>) {
    val arr = JSONArray().apply { songs.forEach { put(it.toJson()) } }
    emitJsEvent("library", arr)
  }

  private fun emitJsEvent(eventType: String, payload: Any) {
    val wv = webViewProvider() ?: return
    val payloadJson = payload.toString()
    mainHandler.post {
      val current = webViewProvider() ?: return@post
      if (!isTrustedAppUrl(current.url)) return@post
      current.evaluateJavascript(
        "window.__pulseNativeEvent && window.__pulseNativeEvent(${JSONObject.quote(eventType)}, $payloadJson);",
        null,
      )
    }
  }

  companion object {
    const val TRUSTED_APP_ORIGIN = "https://appassets.androidplatform.net"

    fun isTrustedAppUrl(url: String?): Boolean {
      if (url.isNullOrBlank()) return false
      return url == TRUSTED_APP_ORIGIN || url.startsWith("$TRUSTED_APP_ORIGIN/")
    }

    fun parseStringList(rawJson: String?): List<String> {
      if (rawJson.isNullOrBlank()) return emptyList()
      return try {
        val arr = JSONArray(rawJson)
        buildList(arr.length()) {
          for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) add(s)
          }
        }
      } catch (_: Exception) {
        emptyList()
      }
    }
  }
}
