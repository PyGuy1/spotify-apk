package com.example.stream

import com.example.PulseLog
import org.json.JSONArray
import org.json.JSONObject

internal object InnerTubeResponseParser {
  fun isPlayableStatus(root: JSONObject): Boolean {
    val status =
      root.optJSONObject("playabilityStatus")?.optString("status", "").orEmpty().uppercase()
    if (status == "OK") return true
    if (status.isBlank()) {
      return root.has("streamingData")
    }
    return false
  }

  fun extractFormats(
    root: JSONObject,
    client: InnerTubeClientType,
    engineTag: String,
    cipherConfig: PlayerCipherConfig?,
    poToken: String?,
    quality: String,
    nowMs: Long,
  ): Pair<List<AudioCandidate>, Double?> {
    val streamingData = root.optJSONObject("streamingData") ?: return emptyList<AudioCandidate>() to null
    val adaptive = streamingData.optJSONArray("adaptiveFormats")
    val legacy = streamingData.optJSONArray("formats")
    val loudnessDb =
      root.optJSONObject("playerConfig")
        ?.optJSONObject("audioConfig")
        ?.let { ac -> if (ac.has("loudnessDb")) ac.optDouble("loudnessDb") else null }

    val rawCandidates = mutableListOf<AudioCandidate>()
    collectFromJsonArray(adaptive, client, engineTag, cipherConfig, poToken, false, rawCandidates)
    collectFromJsonArray(legacy, client, engineTag, cipherConfig, poToken, true, rawCandidates)

    val ranked = StreamFormatRanking.rankCandidates(rawCandidates, quality, nowMs)
    return ranked to loudnessDb
  }

  private fun collectFromJsonArray(
    arr: JSONArray?,
    client: InnerTubeClientType,
    engineTag: String,
    cipherConfig: PlayerCipherConfig?,
    poToken: String?,
    allowMuxedOnly: Boolean,
    out: MutableList<AudioCandidate>,
  ) {
    if (arr == null) return
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val mimeFull = item.optString("mimeType", "").trim()
      val itag = item.optInt("itag", 0)
      val isAudio = mimeFull.startsWith("audio/", ignoreCase = true)
      val isMuxedFallback = allowMuxedOnly && itag in StreamFormatRanking.MUXED_FALLBACK_ITAGS
      if (!isAudio && !isMuxedFallback) continue

      val directUrl = item.optString("url", "")
      val cipher =
        item.optString("signatureCipher", item.optString("cipher", ""))
      val resolvedUrl =
        CipherDeobfuscator.decipherFormatUrl(
          directUrl = directUrl,
          signatureCipher = cipher,
          cipherConfig = cipherConfig,
          poToken = if (client.supportsPoToken) poToken else null,
        ) ?: continue

      val bitrate = item.optInt("averageBitrate", item.optInt("bitrate", 0))
      val contentLen = item.optString("contentLength", "-1").toLongOrNull() ?: -1L
      val sampleRate = item.optString("audioSampleRate", "0").toIntOrNull() ?: 0
      val codecs = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.getOrNull(1).orEmpty()

      out.add(
        AudioCandidate(
          url = resolvedUrl,
          mimeType = mimeFull,
          bitrate = bitrate,
          sourceHost = StreamResolver.extractHostKey(resolvedUrl),
          apiMirrorHost = client.originUrl,
          itag = if (itag > 0) itag else StreamFormatRanking.extractItagFromUrl(resolvedUrl),
          engineSource = engineTag,
          clientName = client.profileId,
          contentLength = contentLen,
          codecs = codecs,
          sampleRate = sampleRate,
          isMuxedFallback = isMuxedFallback,
        )
      )
    }
  }
}

/**
 * Primary Extractor (InnerTubeX):
 * Uses live-benchmarked client profiles, remote player cipher configurations (ZemerTeam layout),
 * signature deciphering, and PoToken generation.
 */
class InnerTubeXResolver(
  private val httpClient: StreamHttpClient,
  private val profiler: InnerTubeClientProfiler,
  private val cipherProvider: CipherConfigProvider,
  private val poTokenGenerator: PoTokenGenerator,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val customPlayerBaseUrl: String? = null,
) {
  suspend fun resolve(
    trackId: String,
    quality: String,
    forcePoToken: Boolean = false,
  ): ResolvedStream? {
    cipherProvider.ensureRemoteConfigsLoaded()
    val poBundle = poTokenGenerator.getOrGenerate(forceRefresh = forcePoToken)
    val baseSts = cipherProvider.latestKnownSts()
    val rankedProfiles = profiler.rankedClients(InnerTubeClientType.CASCADE_ORDER)

    // Probe top live-benchmarked profiles; short-circuit on first valid playable audio stream
    for (profile in rankedProfiles.take(5)) {
      val startedMs = clock()
      try {
        val url = customPlayerBaseUrl?.let { "${it.trimEnd('/')}/youtubei/v1/player?prettyPrint=false" }
          ?: profile.playerEndpointUrl()
        val payload =
          profile.buildPlayerPayload(
            videoId = trackId,
            poTokenBundle = poBundle,
            signatureTimestamp = baseSts,
            includePoToken = forcePoToken,
          )
        val raw =
          httpClient.postJsonSuspend(
            url = url,
            jsonBody = payload,
            headers = profile.buildHeaders(poBundle.visitorData),
          )
        val root = JSONObject(raw)
        if (!InnerTubeResponseParser.isPlayableStatus(root)) {
          profiler.recordFailure(profile)
          continue
        }

        // Resolve player cipher config if any adaptive format requires signatureCipher decoding
        val cipherCfg = resolveCipherConfigIfNeeded(trackId, root, baseSts)
        val now = clock()
        val (candidates, loudness) =
          InnerTubeResponseParser.extractFormats(
            root = root,
            client = profile,
            engineTag = "innertubex",
            cipherConfig = cipherCfg,
            poToken = if (forcePoToken) poBundle.poToken else null,
            quality = quality,
            nowMs = now,
          )

        if (candidates.isNotEmpty()) {
          profiler.recordSuccess(profile, (clock() - startedMs).coerceAtLeast(1L))
          PulseLog.d(
            TAG,
            "InnerTubeX resolved $trackId via ${profile.profileId} (${candidates.size} candidates, top itag=${candidates.first().itag})",
          )
          return ResolvedStream(
            trackId = trackId,
            quality = quality,
            audioUrls = candidates.map { it.url },
            candidates = candidates,
            resolvedAtMs = now,
            expiresAtMs = now + StreamResolver.STREAM_CACHE_TTL_MS,
            fromApiMirror = true,
            engineUsed = "innertubex",
            clientUsed = profile.profileId,
            loudnessDb = loudness,
          )
        }
        profiler.recordFailure(profile)
      } catch (e: Exception) {
        profiler.recordFailure(profile)
        PulseLog.d(TAG, "InnerTubeX profile ${profile.profileId} failed for $trackId: ${e.message}")
      }
    }
    return null
  }

  private suspend fun resolveCipherConfigIfNeeded(
    trackId: String,
    root: JSONObject,
    fallbackSts: Int,
  ): PlayerCipherConfig? {
    val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
    var needsCipher = false
    for (i in 0 until formats.length()) {
      val item = formats.optJSONObject(i) ?: continue
      if (item.has("signatureCipher") || item.has("cipher")) {
        needsCipher = true
        break
      }
    }
    if (!needsCipher) return null

    val playerUrl =
      root.optJSONObject("playerConfig")?.optString("jsUrl", "")
        ?.ifBlank { root.optJSONObject("responseContext")?.optString("serviceTrackingParams", "") }
        .orEmpty()
    if (playerUrl.contains("/s/player/")) {
      cipherProvider.fetchOrParseFromPlayerJs(playerUrl)?.let { return it }
    }
    return try {
      val embedHtml =
        httpClient.getTextWithHeadersSuspend(
          "https://www.youtube.com/embed/$trackId",
          mapOf(OkHttpStreamClient.HEADER_ALLOW_HTML to "1"),
        )
      val jsPath =
        Regex("(/s/player/[a-zA-Z0-9_-]+/[^\"'\\\\\\s]+base\\.js)")
          .find(embedHtml)
          ?.groupValues
          ?.getOrNull(1)
      if (!jsPath.isNullOrBlank()) {
        cipherProvider.fetchOrParseFromPlayerJs(jsPath)
      } else {
        null
      }
    } catch (_: Exception) {
      PlayerCipherConfig(playerId = "sts_$fallbackSts", signatureTimestamp = fallbackSts, sigSteps = emptyList())
    }
  }

  companion object {
    private const val TAG = "InnerTubeXResolver"
  }
}

/**
 * Secondary Extractor:
 * Sequential InnerTube Client Cascade in exact specified client priority order:
 * WEB_REMIX -> VISIONOS -> ANDROID_VR_1_65_10 -> TVHTML5 -> ANDROID_VR_1_43_32 -> WEB_CREATOR -> IPADOS -> IOS
 */
class InnerTubeCascadeResolver(
  private val httpClient: StreamHttpClient,
  private val cipherProvider: CipherConfigProvider,
  private val poTokenGenerator: PoTokenGenerator,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val customPlayerBaseUrl: String? = null,
) {
  suspend fun resolve(
    trackId: String,
    quality: String,
    forcePoToken: Boolean = false,
  ): ResolvedStream? {
    val poBundle = poTokenGenerator.getOrGenerate(forceRefresh = forcePoToken)
    val sts = cipherProvider.latestKnownSts()

    for (client in InnerTubeClientType.CASCADE_ORDER) {
      try {
        val url = customPlayerBaseUrl?.let { "${it.trimEnd('/')}/youtubei/v1/player?prettyPrint=false" }
          ?: client.playerEndpointUrl()
        val payload =
          client.buildPlayerPayload(
            videoId = trackId,
            poTokenBundle = poBundle,
            signatureTimestamp = sts,
            includePoToken = forcePoToken,
          )
        val raw =
          httpClient.postJsonSuspend(
            url = url,
            jsonBody = payload,
            headers = client.buildHeaders(poBundle.visitorData),
          )
        val root = JSONObject(raw)
        if (!InnerTubeResponseParser.isPlayableStatus(root)) continue

        val cipherCfg =
          if (client.requiresCipherOrSts) {
            cipherProvider.getRemoteConfig("default")
              ?: PlayerCipherConfig("sts_$sts", sts, emptyList())
          } else {
            null
          }
        val now = clock()
        val (candidates, loudness) =
          InnerTubeResponseParser.extractFormats(
            root = root,
            client = client,
            engineTag = "innertube",
            cipherConfig = cipherCfg,
            poToken = if (forcePoToken) poBundle.poToken else null,
            quality = quality,
            nowMs = now,
          )
        if (candidates.isNotEmpty()) {
          PulseLog.d(
            TAG,
            "InnerTube cascade resolved $trackId at ${client.profileId} (${candidates.size} candidates)",
          )
          return ResolvedStream(
            trackId = trackId,
            quality = quality,
            audioUrls = candidates.map { it.url },
            candidates = candidates,
            resolvedAtMs = now,
            expiresAtMs = now + StreamResolver.STREAM_CACHE_TTL_MS,
            fromApiMirror = true,
            engineUsed = "innertube",
            clientUsed = client.profileId,
            loudnessDb = loudness,
          )
        }
      } catch (e: Exception) {
        PulseLog.d(TAG, "InnerTube cascade ${client.profileId} failed for $trackId: ${e.message}")
      }
    }
    return null
  }

  companion object {
    private const val TAG = "InnerTubeCascade"
  }
}
