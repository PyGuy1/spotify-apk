package com.example.stream

import com.example.PulseLog
import org.json.JSONObject

/**
 * Independent NewPipeExtractor-compatible extraction engine (BravePipe).
 * Extracts stream data directly from watch/embed HTML `ytInitialPlayerResponse` and player JS
 * signature deciphering without depending on `youtubei/v1/player` or Piped JSON mirrors.
 */
class BravePipeExtractor(
  private val httpClient: StreamHttpClient,
  private val cipherProvider: CipherConfigProvider,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val watchBaseUrl: String = "https://www.youtube.com",
) {
  suspend fun extractStreams(trackId: String, quality: String): ResolvedStream? {
    if (trackId.isBlank() || trackId.startsWith("local_")) return null

    // Try desktop/mobile watch page first, then embed page fallback
    val watchUrls =
      listOf(
        "${watchBaseUrl.trimEnd('/')}/watch?v=$trackId&hl=en&bpctr=9999999999&has_verified=1",
        "${watchBaseUrl.trimEnd('/')}/embed/$trackId?hl=en",
      )

    for (pageUrl in watchUrls) {
      try {
        val html =
          httpClient.getTextWithHeadersSuspend(
            pageUrl,
            mapOf(
              OkHttpStreamClient.HEADER_ALLOW_HTML to "1",
              "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
              "Accept-Language" to "en-US,en;q=0.9",
            ),
          )
        val playerJson = extractPlayerResponseJson(html) ?: continue
        val root = JSONObject(playerJson)
        if (!InnerTubeResponseParser.isPlayableStatus(root)) continue

        val jsPath = extractPlayerJsPath(html)
        val cipherCfg =
          if (!jsPath.isNullOrBlank()) {
            val fullJsUrl =
              if (jsPath.startsWith("http")) jsPath
              else "${watchBaseUrl.trimEnd('/')}$jsPath"
            cipherProvider.fetchOrParseFromPlayerJs(fullJsUrl)
          } else {
            cipherProvider.getRemoteConfig("default")
          }

        val now = clock()
        val rawCandidates = mutableListOf<AudioCandidate>()
        val streaming = root.optJSONObject("streamingData") ?: continue

        collectFormats(streaming.optJSONArray("adaptiveFormats"), cipherCfg, false, rawCandidates)
        collectFormats(streaming.optJSONArray("formats"), cipherCfg, true, rawCandidates)

        val ranked = StreamFormatRanking.rankCandidates(rawCandidates, quality, now)
        if (ranked.isNotEmpty()) {
          val loudness =
            root.optJSONObject("playerConfig")
              ?.optJSONObject("audioConfig")
              ?.let { if (it.has("loudnessDb")) it.optDouble("loudnessDb") else null }
          PulseLog.d(
            TAG,
            "BravePipe extracted $trackId (${ranked.size} streams, top itag=${ranked.first().itag})",
          )
          return ResolvedStream(
            trackId = trackId,
            quality = quality,
            audioUrls = ranked.map { it.url },
            candidates = ranked,
            resolvedAtMs = now,
            expiresAtMs = now + StreamResolver.STREAM_CACHE_TTL_MS,
            fromApiMirror = true,
            engineUsed = "bravepipe",
            clientUsed = "BravePipe",
            loudnessDb = loudness,
          )
        }
      } catch (e: Exception) {
        PulseLog.d(TAG, "BravePipe attempt failed ($pageUrl) for $trackId: ${e.message}")
      }
    }
    return null
  }

  private fun collectFormats(
    arr: org.json.JSONArray?,
    cipherCfg: PlayerCipherConfig?,
    allowMuxed: Boolean,
    out: MutableList<AudioCandidate>,
  ) {
    if (arr == null) return
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val mimeFull = item.optString("mimeType", "").trim()
      val itag = item.optInt("itag", 0)
      val isAudio = mimeFull.startsWith("audio/", ignoreCase = true)
      val isMuxedFallback = allowMuxed && itag in StreamFormatRanking.MUXED_FALLBACK_ITAGS
      if (!isAudio && !isMuxedFallback) continue

      val directUrl = item.optString("url", "")
      val cipher = item.optString("signatureCipher", item.optString("cipher", ""))
      val finalUrl =
        CipherDeobfuscator.decipherFormatUrl(
          directUrl = directUrl,
          signatureCipher = cipher,
          cipherConfig = cipherCfg,
          poToken = null,
        ) ?: continue

      val bitrate = item.optInt("averageBitrate", item.optInt("bitrate", 0))
      val contentLen = item.optString("contentLength", "-1").toLongOrNull() ?: -1L
      val sampleRate = item.optString("audioSampleRate", "0").toIntOrNull() ?: 0
      val codecs = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.getOrNull(1).orEmpty()

      out.add(
        AudioCandidate(
          url = finalUrl,
          mimeType = mimeFull,
          bitrate = bitrate,
          sourceHost = StreamResolver.extractHostKey(finalUrl),
          apiMirrorHost = watchBaseUrl,
          itag = if (itag > 0) itag else StreamFormatRanking.extractItagFromUrl(finalUrl),
          engineSource = "bravepipe",
          clientName = "BravePipe",
          contentLength = contentLen,
          codecs = codecs,
          sampleRate = sampleRate,
          isMuxedFallback = isMuxedFallback,
        )
      )
    }
  }

  companion object {
    private const val TAG = "BravePipeExtractor"

    internal fun extractPlayerJsPath(html: String): String? =
      Regex("(/s/player/[a-zA-Z0-9_-]+/[^\"'\\\\\\s]+base\\.js)")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)

    internal fun extractPlayerResponseJson(html: String): String? {
      val markers =
        listOf(
          "ytInitialPlayerResponse = ",
          "ytInitialPlayerResponse=",
          "\"PLAYER_RESPONSE\":",
        )
      for (marker in markers) {
        val idx = html.indexOf(marker)
        if (idx < 0) continue
        val braceStart = html.indexOf('{', idx + marker.length)
        if (braceStart < 0) continue
        extractBalancedJsonObject(html, braceStart)?.let { return it }
      }
      return null
    }

    private fun extractBalancedJsonObject(source: String, startIndex: Int): String? {
      var depth = 0
      var inString = false
      var escaped = false
      for (i in startIndex until source.length) {
        val ch = source[i]
        if (inString) {
          if (escaped) {
            escaped = false
          } else if (ch == '\\') {
            escaped = true
          } else if (ch == '"') {
            inString = false
          }
          continue
        }
        when (ch) {
          '"' -> inString = true
          '{' -> depth++
          '}' -> {
            depth--
            if (depth == 0) {
              return source.substring(startIndex, i + 1)
            }
          }
        }
      }
      return null
    }
  }
}
