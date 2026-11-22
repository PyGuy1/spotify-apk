package com.example.stream

import com.example.model.PulseTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface StreamHttpClient {
  @Throws(IOException::class)
  fun getText(url: String): String
}

class OkHttpStreamClient(
  val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
) : StreamHttpClient {
  override fun getText(url: String): String {
    val request =
      Request.Builder()
        .url(url)
        .header(
          "User-Agent",
          "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
        )
        .build()
    okHttpClient.newCall(request).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $url")
      val body = resp.body?.string().orEmpty()
      if (body.isBlank()) throw IOException("Empty response from $url")
      return body
    }
  }
}

data class AudioCandidate(
  val url: String,
  val mimeType: String,
  val bitrate: Int,
  val sourceHost: String,
)

data class ResolvedStream(
  val trackId: String,
  val quality: String,
  val audioUrls: List<String>,
  val relatedTracks: List<PulseTrack>,
  val resolvedAtMs: Long,
  val expiresAtMs: Long,
) {
  fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

class StreamResolver(
  private val httpClient: StreamHttpClient = OkHttpStreamClient(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val resolveMutex = Mutex()
  private val inflightResolves = mutableMapOf<String, CompletableDeferred<ResolvedStream?>>()
  private val cache = ConcurrentHashMap<String, ResolvedStream>()
  private val prefetchJobs = ConcurrentHashMap<String, Job>()
  private val hostScores = ConcurrentHashMap<String, HostScore>()

  @Volatile private var pipedHosts: List<String> = DEFAULT_PIPED
  @Volatile private var invidiousHosts: List<String> = DEFAULT_INVIDIOUS
  @Volatile private var customServerUrl: String = ""
  @Volatile private var lastDiscoveryMs: Long = 0L

  private data class HostScore(var ok: Int = 0, var fail: Int = 0) {
    fun score(): Double = ok - 1.5 * fail
  }

  fun setCustomServer(url: String) {
    customServerUrl = url.trim().trimEnd('/')
  }

  fun clearCache() {
    cache.clear()
  }

  fun invalidate(trackId: String) {
    cache.keys.filter { it.startsWith("$trackId|") }.forEach { cache.remove(it) }
  }

  fun reportHostResult(urlOrHost: String, success: Boolean) {
    val host = extractHostKey(urlOrHost)
    if (host.isBlank()) return
    val current = hostScores.getOrPut(host) { HostScore() }
    synchronized(current) {
      if (success) current.ok++ else current.fail++
      if (current.ok + current.fail > 40) {
        current.ok = (current.ok + 1) / 2
        current.fail = (current.fail + 1) / 2
      }
    }
  }

  fun getCachedStream(trackId: String, quality: String = "normal"): ResolvedStream? {
    val key = "$trackId|$quality"
    val entry = cache[key] ?: return null
    if (entry.isExpired(clock())) {
      cache.remove(key)
      return null
    }
    return entry
  }

  suspend fun resolveStream(
    trackId: String,
    quality: String = "normal",
    forceRefresh: Boolean = false,
  ): ResolvedStream? {
    if (trackId.isBlank() || trackId.startsWith("local_")) return null
    val normQuality = normalizeQuality(quality)
    val key = "$trackId|$normQuality"

    if (!forceRefresh) {
      getCachedStream(trackId, normQuality)?.let { return it }
    } else {
      cache.remove(key)
    }

    val existing: CompletableDeferred<ResolvedStream?>
    val creator: Boolean
    resolveMutex.withLock {
      val current = inflightResolves[key]
      if (current != null && !current.isCompleted) {
        existing = current
        creator = false
      } else {
        val created = CompletableDeferred<ResolvedStream?>()
        inflightResolves[key] = created
        existing = created
        creator = true
      }
    }

    if (!creator) {
      return existing.await()
    }

    return try {
      maybeRefreshInstancesAsync()
      val result = fetchFromMirrors(trackId, normQuality)
      if (result != null && result.audioUrls.isNotEmpty()) {
        cache[key] = result
      }
      existing.complete(result)
      result
    } catch (c: CancellationException) {
      existing.cancel(c)
      throw c
    } catch (t: Throwable) {
      existing.complete(null)
      null
    } finally {
      resolveMutex.withLock {
        if (inflightResolves[key] === existing) {
          inflightResolves.remove(key)
        }
      }
    }
  }

  fun schedulePrefetch(
    upcomingTrackIds: List<String>,
    quality: String = "normal",
  ) {
    val keepSet = upcomingTrackIds.filter { it.isNotBlank() && !it.startsWith("local_") }.toSet()
    prefetchJobs.entries.toList().forEach { (id, job) ->
      if (!keepSet.contains(id)) {
        job.cancel()
        prefetchJobs.remove(id)
      }
    }
    keepSet.forEach { trackId ->
      if (getCachedStream(trackId, normalizeQuality(quality)) != null) return@forEach
      val existing = prefetchJobs[trackId]
      if (existing != null && existing.isActive) return@forEach
      val job =
        scope.launch {
          try {
            resolveStream(trackId, quality)
          } finally {
            prefetchJobs.remove(trackId)
          }
        }
      prefetchJobs[trackId] = job
    }
  }

  fun cancelAllPrefetches() {
    prefetchJobs.values.forEach { it.cancel() }
    prefetchJobs.clear()
  }

  suspend fun fetchRelatedTracks(trackId: String): List<PulseTrack> {
    if (trackId.isBlank() || trackId.startsWith("local_")) return emptyList()
    getCachedStream(trackId, "normal")?.relatedTracks?.takeIf { it.isNotEmpty() }?.let { return it }
    return resolveStream(trackId, "normal")?.relatedTracks.orEmpty()
  }

  private fun maybeRefreshInstancesAsync() {
    val now = clock()
    if (now - lastDiscoveryMs < INSTANCE_REFRESH_TTL_MS) return
    lastDiscoveryMs = now
    scope.launch {
      try {
        val pipedText = httpClient.getText("https://piped-instances.kavin.rocks/")
        val arr = JSONArray(pipedText)
        val discovered =
          buildList {
            for (i in 0 until arr.length()) {
              val obj = arr.optJSONObject(i) ?: continue
              val apiUrl = obj.optString("api_url", "").trim().trimEnd('/')
              val uptime = obj.optDouble("uptime_30d", 100.0)
              if (apiUrl.startsWith("https://") && uptime > 50.0) {
                add(apiUrl to uptime)
              }
            }
          }
            .sortedByDescending { it.second }
            .map { it.first }
        if (discovered.isNotEmpty()) {
          pipedHosts = (discovered.take(3) + DEFAULT_PIPED + discovered.drop(3).take(6)).distinct()
        }
      } catch (_: Exception) {}

      try {
        val invText =
          httpClient.getText("https://api.invidious.io/instances.json?sort_by=type,health")
        val arr = JSONArray(invText)
        val discovered =
          buildList {
            for (i in 0 until arr.length()) {
              val pair = arr.optJSONArray(i) ?: continue
              val meta = pair.optJSONObject(1) ?: continue
              val type = meta.optString("type", "")
              val api = meta.optBoolean("api", false)
              val uri = meta.optString("uri", "").trim().trimEnd('/')
              if (type == "https" && api && uri.startsWith("https://")) {
                add(uri)
              }
            }
          }
        if (discovered.isNotEmpty()) {
          invidiousHosts =
            (discovered.take(3) + DEFAULT_INVIDIOUS + discovered.drop(3).take(5)).distinct()
        }
      } catch (_: Exception) {}
    }
  }

  private suspend fun fetchFromMirrors(trackId: String, quality: String): ResolvedStream? =
    coroutineScope {
      val endpoints = buildEndpoints(trackId)
      val collectedUrls = mutableListOf<String>()
      var related: List<PulseTrack> = emptyList()

      val jobs =
        endpoints.map { endpoint ->
          async(Dispatchers.IO) {
            try {
              val raw = httpClient.getText(endpoint.requestUrl)
              val parsed = parseEndpointResponse(raw, endpoint.hostBase, quality)
              if (parsed.urls.isNotEmpty()) {
                reportHostResult(endpoint.hostBase, true)
                parsed
              } else {
                reportHostResult(endpoint.hostBase, false)
                null
              }
            } catch (_: CancellationException) {
              throw CancellationException("Cancelled")
            } catch (_: Exception) {
              reportHostResult(endpoint.hostBase, false)
              null
            }
          }
        }

      for (deferred in jobs) {
        val res =
          try {
            deferred.await()
          } catch (_: Exception) {
            null
          }
        if (res != null) {
          collectedUrls.addAll(res.urls)
          if (related.isEmpty() && res.related.isNotEmpty()) {
            related = res.related
          }
          if (collectedUrls.size >= 3 && related.isNotEmpty()) {
            break
          }
        }
      }

      // Add Invidious direct proxy audio endpoints as resilient fallbacks.
      val itag = if (quality == "low") 139 else 140
      rankedHosts(invidiousHosts, 5).forEach { host ->
        collectedUrls.add("$host/latest_version?id=$trackId&itag=$itag&local=true")
      }

      val uniqueUrls = collectedUrls.filter { isValidStreamUrl(it) }.distinct()
      if (uniqueUrls.isEmpty()) return@coroutineScope null
      val now = clock()
      ResolvedStream(
        trackId = trackId,
        quality = quality,
        audioUrls = uniqueUrls,
        relatedTracks = related,
        resolvedAtMs = now,
        expiresAtMs = now + STREAM_CACHE_TTL_MS,
      )
    }

  private data class EndpointTarget(val hostBase: String, val requestUrl: String)

  private fun buildEndpoints(trackId: String): List<EndpointTarget> {
    val custom = customServerUrl
    val targets = mutableListOf<EndpointTarget>()
    if (custom.isNotBlank()) {
      targets.add(EndpointTarget(custom, "$custom/streams/$trackId"))
      targets.add(EndpointTarget(custom, "$custom/api/v1/videos/$trackId?local=true"))
    }
    rankedHosts(pipedHosts, 6).forEach { host ->
      targets.add(EndpointTarget(host, "$host/streams/$trackId"))
    }
    rankedHosts(invidiousHosts, 5).forEach { host ->
      targets.add(EndpointTarget(host, "$host/api/v1/videos/$trackId?local=true"))
    }
    return targets
  }

  internal data class ParsedMirror(val urls: List<String>, val related: List<PulseTrack>)

  internal fun parseEndpointResponse(
    rawJson: String,
    hostBase: String,
    quality: String,
  ): ParsedMirror {
    val root = JSONObject(rawJson)
    val candidates = mutableListOf<AudioCandidate>()
    var related = emptyList<PulseTrack>()

    if (root.has("audioStreams")) {
      val arr = root.optJSONArray("audioStreams")
      if (arr != null) {
        for (i in 0 until arr.length()) {
          val s = arr.optJSONObject(i) ?: continue
          val url = s.optString("url", "").trim()
          val mime = s.optString("mimeType", "").trim()
          val bitrate = s.optInt("bitrate", 0)
          if (isValidStreamUrl(url)) {
            candidates.add(AudioCandidate(url, mime, bitrate, hostBase))
          }
        }
      }
      related = parseTracksArray(root.optJSONArray("relatedStreams"))
    } else if (root.has("adaptiveFormats")) {
      val arr = root.optJSONArray("adaptiveFormats")
      if (arr != null) {
        for (i in 0 until arr.length()) {
          val s = arr.optJSONObject(i) ?: continue
          val mime = s.optString("type", "").trim()
          if (!mime.startsWith("audio", ignoreCase = true)) continue
          val url = s.optString("url", "").trim()
          val bitrate = s.optString("bitrate", "0").toIntOrNull() ?: s.optInt("bitrate", 0)
          if (isValidStreamUrl(url)) {
            candidates.add(AudioCandidate(url, mime, bitrate, hostBase))
          }
        }
      }
      related = parseTracksArray(root.optJSONArray("recommendedVideos"))
    }

    val rankedUrls = rankAudioCandidates(candidates, quality).map { it.url }
    return ParsedMirror(urls = rankedUrls, related = related)
  }

  private fun parseTracksArray(arr: JSONArray?): List<PulseTrack> {
    if (arr == null) return emptyList()
    return buildList {
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val type = obj.optString("type", "")
        if (type.isNotBlank() && type != "stream") continue
        val url = obj.optString("url", "")
        val id =
          obj.optString("videoId", "").ifBlank {
            Regex("[?&]v=([\\w-]{11})").find(url)?.groupValues?.getOrNull(1).orEmpty()
          }
        if (id.isBlank()) continue
        val rawAuthor = obj.optString("author", obj.optString("uploaderName", ""))
        val artist = rawAuthor.replace(TOPIC_SUFFIX_REGEX, "").trim()
        val duration =
          obj.optInt("lengthSeconds", obj.optInt("duration", 0)).takeIf { it > 0 }
        if (duration != null && (duration < 60 || duration > 600)) continue
        val title = obj.optString("title", obj.optString("name", "")).trim()
        if (title.isBlank()) continue
        val thumb = obj.optString("thumbnail", "").ifBlank {
          "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        }
        add(
          PulseTrack(
            id = id,
            title = title,
            artist = artist,
            durationSec = duration,
            thumbUrl = thumb,
            tp = if (TOPIC_SUFFIX_REGEX.containsMatchIn(rawAuthor)) 1 else 0,
            v = if (obj.optBoolean("uploaderVerified", obj.optBoolean("authorVerified", false))) 1 else 0,
            ms = if (thumb.contains("googleusercontent") || thumb.contains("ggpht")) 1 else 0,
            auto = 1,
          )
        )
      }
    }
  }

  private fun rankedHosts(hosts: List<String>, limit: Int): List<String> =
    hosts
      .sortedByDescending { host ->
        val s = hostScores[extractHostKey(host)]
        s?.score() ?: 0.0
      }
      .take(limit)

  companion object {
    const val STREAM_CACHE_TTL_MS = 18 * 60 * 1000L
    const val INSTANCE_REFRESH_TTL_MS = 6 * 60 * 60 * 1000L
    private val TOPIC_SUFFIX_REGEX = Regex("\\s*-\\s*Topic$")

    val DEFAULT_PIPED =
      listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
      )

    val DEFAULT_INVIDIOUS =
      listOf(
        "https://yewtu.be",
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
      )

    fun normalizeQuality(quality: String): String =
      when (quality.lowercase()) {
        "low", "high" -> quality.lowercase()
        else -> "normal"
      }

    fun isValidStreamUrl(url: String?): Boolean {
      if (url.isNullOrBlank()) return false
      val lower = url.trim().lowercase()
      return lower.startsWith("https://") || lower.startsWith("http://")
    }

    fun rankAudioCandidates(candidates: List<AudioCandidate>, quality: String): List<AudioCandidate> {
      val audioOnly =
        candidates.filter { cand ->
          isValidStreamUrl(cand.url) &&
            (cand.mimeType.isBlank() ||
              cand.mimeType.contains("audio", ignoreCase = true) ||
              cand.mimeType.contains("mp4a", ignoreCase = true) ||
              cand.mimeType.contains("opus", ignoreCase = true))
        }
      if (audioOnly.isEmpty()) return emptyList()
      val targetBitrate =
        when (normalizeQuality(quality)) {
          "low" -> 50_000
          "high" -> 320_000
          else -> 128_000
        }
      return audioOnly.sortedWith(
        compareBy<AudioCandidate> { cand ->
          when {
            cand.mimeType.contains("mp4", ignoreCase = true) ||
              cand.mimeType.contains("m4a", ignoreCase = true) ||
              cand.mimeType.contains("aac", ignoreCase = true) -> 0
            cand.mimeType.contains("webm", ignoreCase = true) ||
              cand.mimeType.contains("opus", ignoreCase = true) ||
              cand.mimeType.contains("ogg", ignoreCase = true) -> 1
            else -> 2
          }
        }.thenBy { cand -> kotlin.math.abs((if (cand.bitrate > 0) cand.bitrate else 128_000) - targetBitrate) }
      )
    }

    fun extractHostKey(urlOrHost: String): String =
      try {
        val uri = URI(urlOrHost)
        uri.host ?: urlOrHost
      } catch (_: Exception) {
        urlOrHost
      }
  }
}
