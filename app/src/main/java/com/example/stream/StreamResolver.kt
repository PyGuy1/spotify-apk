package com.example.stream

import com.example.PulseLog
import com.example.model.PlaybackEngineMode
import com.example.model.PulseTrack
import com.example.model.SelfHostedServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class StreamResolver(
  private val httpClient: StreamHttpClient = OkHttpStreamClient(),
  private val clock: () -> Long = { System.currentTimeMillis() },
  transportProvider: () -> NetworkTransportKind = { NetworkTransportKind.WIFI_OR_UNMETERED },
  customWatchBaseUrl: String = "https://www.youtube.com",
  customInnerTubeBaseUrl: String? = null,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val resolveMutex = Mutex()
  private val inflightResolves = mutableMapOf<String, CompletableDeferred<ResolvedStream?>>()
  private val cache = ConcurrentHashMap<String, ResolvedStream>()
  private val seededUrls = ConcurrentHashMap<String, List<String>>()
  private val prefetchJobs = ConcurrentHashMap<String, Job>()
  private val hostScores = ConcurrentHashMap<String, HostScore>()

  val clientProfiler = InnerTubeClientProfiler(clock)
  val cipherProvider = CipherConfigProvider(httpClient, clock)
  val poTokenGenerator = PoTokenGenerator(clock)
  val networkQualityManager = NetworkQualityManager(transportProvider)
  val selfHostedResolver = SelfHostedAudioResolver()

  private val innerTubeX =
    InnerTubeXResolver(
      httpClient = httpClient,
      profiler = clientProfiler,
      cipherProvider = cipherProvider,
      poTokenGenerator = poTokenGenerator,
      clock = clock,
      customPlayerBaseUrl = customInnerTubeBaseUrl,
    )
  private val innerTubeCascade =
    InnerTubeCascadeResolver(
      httpClient = httpClient,
      cipherProvider = cipherProvider,
      poTokenGenerator = poTokenGenerator,
      clock = clock,
      customPlayerBaseUrl = customInnerTubeBaseUrl,
    )
  private val bravePipe =
    BravePipeExtractor(
      httpClient = httpClient,
      cipherProvider = cipherProvider,
      clock = clock,
      watchBaseUrl = customWatchBaseUrl,
    )

  val ytPlayerUtils: YTPlayerUtils =
    YTPlayerUtils(
      innerTubeXResolver = innerTubeX,
      innerTubeCascadeResolver = innerTubeCascade,
      bravePipeExtractor = bravePipe,
      selfHostedResolver = selfHostedResolver,
      qualityManager = networkQualityManager,
      pipedFallbackResolver = { trackId, quality, includeSeeded ->
        fetchFromPipedMirrors(trackId, quality, includeSeeded)
      },
    )

  @Volatile private var pipedHosts: List<String> = DEFAULT_PIPED
  @Volatile private var invidiousHosts: List<String> = DEFAULT_INVIDIOUS
  @Volatile private var customServerUrl: String = ""
  @Volatile private var lastDiscoveryMs: Long = 0L

  private data class HostScore(var ok: Int = 0, var fail: Int = 0) {
    fun score(): Double = ok - 1.5 * fail
  }

  fun setEngineMode(mode: PlaybackEngineMode) {
    if (ytPlayerUtils.engineMode != mode) {
      ytPlayerUtils.engineMode = mode
      clearCache()
    }
  }

  fun getEngineMode(): PlaybackEngineMode = ytPlayerUtils.engineMode

  fun setWifiQuality(quality: String) {
    networkQualityManager.setWifiQuality(quality)
    clearCache()
  }

  fun setCellularQuality(quality: String) {
    networkQualityManager.setCellularQuality(quality)
    clearCache()
  }

  fun setSelfHostedServer(config: SelfHostedServerConfig) {
    ytPlayerUtils.setSelfHostedConfig(config)
    clearCache()
  }

  fun setCustomServer(url: String) {
    customServerUrl = url.trim().trimEnd('/')
  }

  fun setDiscoveredMirrors(piped: List<String>, invidious: List<String>) {
    val isAllowedScheme = { u: String ->
      u.startsWith("https://") ||
        u.startsWith("http://127.0.0.1") ||
        u.startsWith("http://localhost")
    }
    val cleanPiped = piped.map { it.trim().trimEnd('/') }.filter(isAllowedScheme)
    val cleanInv = invidious.map { it.trim().trimEnd('/') }.filter(isAllowedScheme)
    if (cleanPiped.isNotEmpty()) {
      pipedHosts = (cleanPiped.take(6) + DEFAULT_PIPED + cleanPiped.drop(6)).distinct()
      lastDiscoveryMs = clock()
    }
    if (cleanInv.isNotEmpty()) {
      invidiousHosts = (cleanInv.take(6) + DEFAULT_INVIDIOUS + cleanInv.drop(6)).distinct()
      lastDiscoveryMs = clock()
    }
    PulseLog.d(
      TAG,
      "Updated mirror pool from app: piped=${pipedHosts.size}, invidious=${invidiousHosts.size}",
    )
  }

  fun seedCandidateUrls(trackId: String, quality: String, rawUrls: List<String>) {
    if (trackId.isBlank() || trackId.startsWith("local_")) return
    val normQuality = normalizeQuality(quality)
    val clean = rawUrls.map { it.trim() }.filter { isValidStreamUrl(it) }.distinct()
    if (clean.isEmpty()) return
    seededUrls["$trackId|$normQuality"] = clean
  }

  fun clearCache() {
    cache.clear()
  }

  fun invalidate(trackId: String) {
    cache.keys.filter { it.startsWith("$trackId|") }.forEach { cache.remove(it) }
    seededUrls.keys.filter { it.startsWith("$trackId|") }.forEach { seededUrls.remove(it) }
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

  fun reportCandidateResult(candidate: AudioCandidate, success: Boolean) {
    reportHostResult(candidate.sourceHost, success)
    if (!success && candidate.apiMirrorHost.isNotBlank() && candidate.apiMirrorHost != candidate.sourceHost) {
      reportHostResult(candidate.apiMirrorHost, false)
    } else if (success && candidate.apiMirrorHost.isNotBlank()) {
      reportHostResult(candidate.apiMirrorHost, true)
    }
  }

  fun getCachedStream(trackId: String, quality: String = "normal"): ResolvedStream? {
    val key = cacheKey(trackId, quality)
    val entry = cache[key] ?: return null
    if (entry.isExpired(clock())) {
      cache.remove(key)
      return null
    }
    return entry
  }

  private fun cacheKey(trackId: String, quality: String): String =
    "$trackId|$quality|${ytPlayerUtils.engineMode.wireName}"

  suspend fun resolveStreamForTrack(
    track: PulseTrack,
    explicitQuality: String? = null,
    forceRefresh: Boolean = false,
  ): ResolvedStream? {
    if (track.id.isBlank() || track.id.startsWith("local_")) return null
    val normQuality = networkQualityManager.effectiveQuality(explicitQuality)
    val key = cacheKey(track.id, normQuality)

    if (!forceRefresh) {
      getCachedStream(track.id, normQuality)?.let { cached ->
        PulseLog.d(TAG, "Cache hit for ${track.id} ($normQuality): ${cached.audioUrls.size} URLs")
        return cached
      }
    } else {
      cache.remove(key)
      seededUrls.remove("${track.id}|$normQuality")
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
      ensureInstancesWarm()
      val result =
        ytPlayerUtils.playerResponseForPlayback(
          track = track,
          explicitQuality = normQuality,
          overrideMode = ytPlayerUtils.engineMode,
          includeSeeded = !forceRefresh,
        )
      if (result != null && result.audioUrls.isNotEmpty() && result.fromApiMirror) {
        cache[key] = result
        PulseLog.d(
          TAG,
          "Resolved ${track.id} ($normQuality, engine=${result.engineUsed}/${result.clientUsed}) -> ${result.audioUrls.size} stream URLs cached",
        )
      } else if (result != null && result.audioUrls.isNotEmpty()) {
        PulseLog.w(
          TAG,
          "Resolved ${track.id} ($normQuality) with fallback proxy URLs only (${result.audioUrls.size})",
        )
      } else {
        PulseLog.w(TAG, "All streaming engines returned no playable URLs for ${track.id}")
      }
      existing.complete(result)
      result
    } catch (c: CancellationException) {
      existing.cancel(c)
      throw c
    } catch (t: Throwable) {
      PulseLog.e(TAG, "Stream resolution failed for ${track.id}: ${t.message}", t)
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

  suspend fun resolveStream(
    trackId: String,
    quality: String = "normal",
    forceRefresh: Boolean = false,
  ): ResolvedStream? =
    resolveStreamForTrack(
      track = PulseTrack(id = trackId, title = trackId),
      explicitQuality = normalizeQuality(quality),
      forceRefresh = forceRefresh,
    )

  fun schedulePrefetch(upcomingTracks: List<PulseTrack>, quality: String? = null) {
    val keepList = upcomingTracks.filter { it.id.isNotBlank() && !it.id.startsWith("local_") }
    val keepIds = keepList.map { it.id }.toSet()
    prefetchJobs.entries.toList().forEach { (id, job) ->
      if (!keepIds.contains(id)) {
        job.cancel()
        prefetchJobs.remove(id)
      }
    }
    val effectiveQuality = networkQualityManager.effectiveQuality(quality)
    keepList.forEach { track ->
      if (getCachedStream(track.id, effectiveQuality) != null) return@forEach
      val existing = prefetchJobs[track.id]
      if (existing != null && existing.isActive) return@forEach
      val job =
        scope.launch {
          try {
            resolveStreamForTrack(track, explicitQuality = effectiveQuality)
          } finally {
            prefetchJobs.remove(track.id)
          }
        }
      prefetchJobs[track.id] = job
    }
  }

  @JvmName("schedulePrefetchIds")
  fun schedulePrefetch(upcomingTrackIds: List<String>, quality: String = "normal") {
    schedulePrefetch(upcomingTrackIds.map { PulseTrack(id = it, title = it) }, quality)
  }

  fun cancelAllPrefetches() {
    prefetchJobs.values.forEach { it.cancel() }
    prefetchJobs.clear()
  }

  suspend fun fetchRelatedTracks(trackId: String): List<PulseTrack> {
    if (trackId.isBlank() || trackId.startsWith("local_")) return emptyList()
    getCachedStream(trackId, "normal")?.relatedTracks?.takeIf { it.isNotEmpty() }?.let { return it }
    return fetchFromPipedMirrors(trackId, "normal", includeSeeded = false)?.relatedTracks.orEmpty()
  }

  private fun ensureInstancesWarm() {
    val now = clock()
    if (now - lastDiscoveryMs < INSTANCE_REFRESH_TTL_MS) return
    lastDiscoveryMs = now
    scope.launch { refreshInstancesSuspend() }
  }

  private suspend fun refreshInstancesSuspend() = coroutineScope {
    val pipedJob =
      launch(Dispatchers.IO) {
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
            pipedHosts = (DEFAULT_PIPED + discovered).distinct()
            PulseLog.d(TAG, "Discovered ${discovered.size} Piped instances")
          }
        } catch (e: Exception) {
          PulseLog.d(TAG, "Piped discovery skipped: ${e.message}")
        }
      }

    val invJob =
      launch(Dispatchers.IO) {
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
            invidiousHosts = (DEFAULT_INVIDIOUS + discovered.take(6)).distinct()
            PulseLog.d(TAG, "Discovered ${discovered.size} Invidious instances")
          }
        } catch (e: Exception) {
          PulseLog.d(TAG, "Invidious discovery skipped: ${e.message}")
        }
      }

    pipedJob.join()
    invJob.join()
  }

  private class MirrorAttemptResult(val parsed: ParsedMirror?)

  internal suspend fun fetchFromPipedMirrors(
    trackId: String,
    quality: String,
    includeSeeded: Boolean,
  ): ResolvedStream? {
    val endpoints = buildEndpoints(trackId)
    val resultsChannel = Channel<MirrorAttemptResult>(Channel.UNLIMITED)
    val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    endpoints.forEach { endpoint ->
      raceScope.launch {
        val parsed =
          try {
            val raw = httpClient.getTextSuspend(endpoint.requestUrl)
            val res = parseEndpointResponse(raw, endpoint.hostBase, quality)
            if (res.urls.isNotEmpty()) {
              reportHostResult(endpoint.hostBase, true)
              PulseLog.d(
                TAG,
                "Piped/Mirror OK ${extractHostKey(endpoint.hostBase)} -> ${res.urls.size} streams for $trackId",
              )
              res
            } else {
              reportHostResult(endpoint.hostBase, false)
              null
            }
          } catch (c: CancellationException) {
            throw c
          } catch (e: Exception) {
            reportHostResult(endpoint.hostBase, false)
            PulseLog.d(TAG, "Mirror error ${extractHostKey(endpoint.hostBase)}: ${e.message}")
            null
          }
        resultsChannel.trySend(MirrorAttemptResult(parsed))
      }
    }

    val collectedCandidates = mutableListOf<AudioCandidate>()
    var related: List<PulseTrack> = emptyList()
    var workingMirrorCount = 0
    var completedEndpoints = 0

    try {
      val deadlineMs = clock() + 10_000L
      while (completedEndpoints < endpoints.size) {
        val remainingBudget =
          if (workingMirrorCount == 0) (deadlineMs - clock()).coerceAtLeast(50L) else 500L
        val attempt = withTimeoutOrNull(remainingBudget) { resultsChannel.receive() } ?: break
        completedEndpoints++
        val res = attempt.parsed
        if (res != null) {
          workingMirrorCount++
          collectedCandidates.addAll(res.candidates)
          if (related.isEmpty() && res.related.isNotEmpty()) {
            related = res.related
          }
          if (workingMirrorCount >= 2 && collectedCandidates.size >= 4 && related.isNotEmpty()) {
            break
          }
        }
      }
    } finally {
      raceScope.cancel()
      resultsChannel.close()
    }

    val seeded =
      if (includeSeeded) {
        seededUrls["$trackId|$quality"].orEmpty().map { url ->
          AudioCandidate(
            url = url,
            mimeType = "",
            bitrate = 128_000,
            sourceHost = extractHostKey(url),
            apiMirrorHost = extractHostKey(url),
            engineSource = "piped-seed",
          )
        }
      } else {
        emptyList()
      }

    val mergedApiCandidates =
      (collectedCandidates + seeded)
        .filter { StreamFormatRanking.isAudioPlayableCandidate(it, allowMuxedFallback = true, nowMs = clock()) }
        .distinctBy { it.url }

    val fromApiMirror = mergedApiCandidates.isNotEmpty()
    val rankedApi = StreamFormatRanking.rankCandidates(mergedApiCandidates, quality, clock())

    val itag = if (quality == "low") 139 else 140
    val invFallbacks =
      rankedHosts(invidiousHosts, 4).map { host ->
        AudioCandidate(
          url = "$host/latest_version?id=$trackId&itag=$itag&local=true",
          mimeType = "audio/mp4",
          bitrate = if (itag == 139) 48_000 else 128_000,
          sourceHost = extractHostKey(host),
          apiMirrorHost = host,
          itag = itag,
          engineSource = "piped-fallback",
        )
      }

    val finalCandidates = (rankedApi + invFallbacks).distinctBy { it.url }
    val uniqueUrls = finalCandidates.map { it.url }
    if (uniqueUrls.isEmpty()) return null

    val now = clock()
    return ResolvedStream(
      trackId = trackId,
      quality = quality,
      audioUrls = uniqueUrls,
      candidates = finalCandidates,
      relatedTracks = related,
      resolvedAtMs = now,
      expiresAtMs = now + STREAM_CACHE_TTL_MS,
      fromApiMirror = fromApiMirror,
      engineUsed = if (fromApiMirror) "piped" else "invidious-proxy",
      clientUsed = finalCandidates.firstOrNull()?.sourceHost.orEmpty(),
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
    rankedHosts(invidiousHosts, 4).forEach { host ->
      targets.add(EndpointTarget(host, "$host/api/v1/videos/$trackId?local=true"))
    }
    return targets
  }

  internal data class ParsedMirror(
    val urls: List<String>,
    val related: List<PulseTrack>,
    val candidates: List<AudioCandidate> = emptyList(),
  )

  internal fun parseEndpointResponse(
    rawJson: String,
    hostBase: String,
    quality: String,
  ): ParsedMirror {
    val root = JSONObject(rawJson)
    if (root.has("error") && !root.has("audioStreams") && !root.has("adaptiveFormats")) {
      return ParsedMirror(emptyList(), emptyList(), emptyList())
    }
    val candidates = mutableListOf<AudioCandidate>()
    var related = emptyList<PulseTrack>()

    if (root.has("audioStreams")) {
      val arr = root.optJSONArray("audioStreams")
      if (arr != null) {
        for (i in 0 until arr.length()) {
          val s = arr.optJSONObject(i) ?: continue
          if (s.optBoolean("videoOnly", false)) continue
          val url = resolveCandidateUrl(s.optString("url", ""), hostBase)
          val mime = s.optString("mimeType", "").trim()
          val bitrate = s.optInt("bitrate", 0)
          val itag = s.optInt("itag", StreamFormatRanking.extractItagFromUrl(url))
          if (isValidStreamUrl(url) && !isPlaylistOrManifestUrl(url, mime)) {
            candidates.add(
              AudioCandidate(
                url = url,
                mimeType = mime,
                bitrate = bitrate,
                sourceHost = extractHostKey(url),
                apiMirrorHost = hostBase,
                itag = itag,
                engineSource = "piped",
              )
            )
          }
        }
      }
      related = parseTracksArray(root.optJSONArray("relatedStreams"))
    } else if (root.has("adaptiveFormats")) {
      val arr = root.optJSONArray("adaptiveFormats")
      if (arr != null) {
        for (i in 0 until arr.length()) {
          val s = arr.optJSONObject(i) ?: continue
          val mime = s.optString("type", s.optString("mimeType", "")).trim()
          if (!mime.startsWith("audio", ignoreCase = true)) continue
          val url = resolveCandidateUrl(s.optString("url", ""), hostBase)
          val bitrate = s.optString("bitrate", "0").toIntOrNull() ?: s.optInt("bitrate", 0)
          val itag = s.optInt("itag", StreamFormatRanking.extractItagFromUrl(url))
          if (isValidStreamUrl(url) && !isPlaylistOrManifestUrl(url, mime)) {
            candidates.add(
              AudioCandidate(
                url = url,
                mimeType = mime,
                bitrate = bitrate,
                sourceHost = extractHostKey(url),
                apiMirrorHost = hostBase,
                itag = itag,
                engineSource = "invidious",
              )
            )
          }
        }
      }
      related = parseTracksArray(root.optJSONArray("recommendedVideos"))
    }

    val ranked = StreamFormatRanking.rankCandidates(candidates, quality, clock())
    return ParsedMirror(urls = ranked.map { it.url }, related = related, candidates = ranked)
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
        val thumb =
          obj.optString("thumbnail", "").ifBlank {
            obj.optJSONArray("videoThumbnails")?.optJSONObject(0)?.optString("url", "").orEmpty()
          }.ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" }
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
    private const val TAG = "PulseStreamResolver"
    const val STREAM_CACHE_TTL_MS = 18 * 60 * 1000L
    const val INSTANCE_REFRESH_TTL_MS = 6 * 60 * 60 * 1000L
    private val TOPIC_SUFFIX_REGEX = Regex("\\s*-\\s*Topic$")

    val DEFAULT_PIPED = PipedFallbackConstants.MANDATORY_PIPED_INSTANCES

    val DEFAULT_INVIDIOUS =
      listOf(
        "https://yewtu.be",
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://invidious.privacyredirect.com",
      )

    fun normalizeQuality(quality: String): String =
      when (quality.lowercase()) {
        "low", "high" -> quality.lowercase()
        else -> "normal"
      }

    fun resolveCandidateUrl(rawUrl: String, hostBase: String): String {
      val trimmed = rawUrl.trim()
      if (trimmed.isBlank()) return ""
      if (trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("http://", ignoreCase = true)
      ) {
        return trimmed
      }
      return try {
        val base = if (hostBase.isNotBlank()) URI(hostBase) else return trimmed
        base.resolve(trimmed).toString()
      } catch (_: Exception) {
        trimmed
      }
    }

    fun isPlaylistOrManifestUrl(url: String, mime: String): Boolean {
      val lowUrl = url.lowercase()
      val lowMime = mime.lowercase()
      return lowUrl.contains(".m3u8") ||
        lowUrl.contains(".mpd") ||
        lowMime.contains("mpegurl") ||
        lowMime.contains("dash+xml")
    }

    fun isValidStreamUrl(url: String?): Boolean {
      if (url.isNullOrBlank()) return false
      val trimmed = url.trim()
      val lower = trimmed.lowercase()
      if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
      return try {
        val uri = URI(trimmed)
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.rawPath?.lowercase().orEmpty()
        if (host.isBlank()) return false
        if (path == "/watch" || path == "/results" || path == "/search") return false
        if (host.endsWith("ytimg.com") || host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com")) {
          return false
        }
        if (path.endsWith(".html") || path.endsWith(".htm") ||
          path.endsWith(".jpg") || path.endsWith(".jpeg") ||
          path.endsWith(".png") || path.endsWith(".webp") ||
          path.endsWith(".svg")
        ) {
          return false
        }
        true
      } catch (_: Exception) {
        false
      }
    }

    fun rankAudioCandidates(candidates: List<AudioCandidate>, quality: String): List<AudioCandidate> =
      StreamFormatRanking.rankCandidates(candidates, quality)

    fun extractHostKey(urlOrHost: String): String =
      try {
        val uri = URI(urlOrHost)
        uri.host ?: urlOrHost
      } catch (_: Exception) {
        urlOrHost
      }
  }
}
