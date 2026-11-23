package com.example.stream

import com.example.model.PulseTrack
import com.example.model.SelfHostedServerConfig
import com.example.model.SelfHostedServerType
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

class SelfHostedAudioResolver {
  @Volatile private var config: SelfHostedServerConfig = SelfHostedServerConfig()

  fun setConfig(newConfig: SelfHostedServerConfig) {
    config = newConfig
  }

  fun getConfig(): SelfHostedServerConfig = config

  fun resolveIfApplicable(track: PulseTrack, quality: String): ResolvedStream? {
    val srcType = track.sourceType.lowercase()
    val hasDirectUrl =
      track.streamUrl.startsWith("http://", ignoreCase = true) ||
        track.streamUrl.startsWith("https://", ignoreCase = true)
    if (srcType !in setOf("jellyfin", "subsonic", "navidrome") && !hasDirectUrl) {
      return null
    }

    val cfg = config
    val headers = LinkedHashMap<String, String>()
    val url: String =
      when {
        hasDirectUrl -> {
          if (cfg.type == SelfHostedServerType.JELLYFIN && cfg.tokenOrPassword.isNotBlank()) {
            headers["X-Emby-Token"] = cfg.tokenOrPassword
          }
          track.streamUrl
        }
        (srcType == "jellyfin" || cfg.type == SelfHostedServerType.JELLYFIN) && cfg.isConfigured -> {
          val maxBitrate =
            when (StreamResolver.normalizeQuality(quality)) {
              "low" -> 96_000
              "normal" -> 192_000
              else -> 320_000
            }
          val itemId = track.id.removePrefix("jf_").removePrefix("jellyfin_")
          val base = cfg.baseUrl.trimEnd('/')
          if (cfg.tokenOrPassword.isNotBlank()) {
            headers["X-Emby-Token"] = cfg.tokenOrPassword
            headers["Authorization"] =
              "MediaBrowser Client=\"PyMusic\", Device=\"Android\", DeviceId=\"pymusic-android\", Version=\"1.0\", Token=\"${cfg.tokenOrPassword}\""
          }
          val tokenQ =
            if (cfg.tokenOrPassword.isNotBlank()) {
              "&api_key=${URLEncoder.encode(cfg.tokenOrPassword, "UTF-8")}"
            } else {
              ""
            }
          "$base/Audio/${URLEncoder.encode(itemId, "UTF-8")}/stream?static=true&maxStreamingBitrate=$maxBitrate$tokenQ"
        }
        (srcType in setOf("subsonic", "navidrome") ||
          cfg.type == SelfHostedServerType.SUBSONIC ||
          cfg.type == SelfHostedServerType.NAVIDROME) && cfg.isConfigured -> {
          val itemId =
            track.id
              .removePrefix("sub_")
              .removePrefix("subsonic_")
              .removePrefix("nav_")
              .removePrefix("navidrome_")
          val base = cfg.baseUrl.trimEnd('/')
          val maxBitRateKbps =
            when (StreamResolver.normalizeQuality(quality)) {
              "low" -> 96
              "normal" -> 192
              else -> 0 // 0 = raw / unlimited original quality in Subsonic
            }
          val salt = UUID.randomUUID().toString().replace("-", "").take(10)
          val token = md5Hex("${cfg.tokenOrPassword}$salt")
          "$base/rest/stream.view?id=${URLEncoder.encode(itemId, "UTF-8")}" +
            "&u=${URLEncoder.encode(cfg.username, "UTF-8")}" +
            "&t=$token&s=$salt&v=1.16.1&c=PyMusic&f=raw&maxBitRate=$maxBitRateKbps"
        }
        else -> return null
      }

    if (!StreamResolver.isValidStreamUrl(url)) return null

    val cand =
      AudioCandidate(
        url = url,
        mimeType = track.mimeType.ifBlank { "audio/mpeg" },
        bitrate = track.bitrate.takeIf { it > 0 } ?: 320_000,
        sourceHost = StreamResolver.extractHostKey(url),
        apiMirrorHost = cfg.baseUrl.ifBlank { StreamResolver.extractHostKey(url) },
        itag = track.itag,
        engineSource = if (srcType != "youtube") srcType else cfg.type.wireName,
        clientName = "SelfHosted",
        headers = headers,
      )
    return ResolvedStream(
      trackId = track.id,
      quality = quality,
      audioUrls = listOf(cand.url),
      candidates = listOf(cand),
      fromApiMirror = true,
      engineUsed = cand.engineSource,
      clientUsed = "SelfHosted",
    )
  }

  companion object {
    internal fun md5Hex(input: String): String {
      val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { b -> "%02x".format(b) }
    }
  }
}

object PipedFallbackConstants {
  val MANDATORY_PIPED_INSTANCES: List<String> =
    listOf(
      "https://pipedapi.kavin.rocks",
      "https://pipedapi.adminforge.de",
      "https://api.piped.private.coffee",
    )
}
