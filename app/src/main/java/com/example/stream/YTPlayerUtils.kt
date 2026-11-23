package com.example.stream

import com.example.PulseLog
import com.example.model.PlaybackEngineMode
import com.example.model.PulseTrack
import com.example.model.SelfHostedServerConfig

enum class NetworkTransportKind {
  WIFI_OR_UNMETERED,
  CELLULAR_OR_METERED,
}

class NetworkQualityManager(
  private val transportProvider: () -> NetworkTransportKind = { NetworkTransportKind.WIFI_OR_UNMETERED },
) {
  @Volatile private var wifiQuality: String = "high"
  @Volatile private var cellularQuality: String = "normal"

  fun setWifiQuality(quality: String) {
    wifiQuality = StreamResolver.normalizeQuality(quality)
  }

  fun setCellularQuality(quality: String) {
    cellularQuality = StreamResolver.normalizeQuality(quality)
  }

  fun getWifiQuality(): String = wifiQuality

  fun getCellularQuality(): String = cellularQuality

  fun currentTransport(): NetworkTransportKind = transportProvider()

  fun effectiveQuality(explicitOverride: String? = null): String {
    if (!explicitOverride.isNullOrBlank()) {
      return StreamResolver.normalizeQuality(explicitOverride)
    }
    return when (currentTransport()) {
      NetworkTransportKind.WIFI_OR_UNMETERED -> wifiQuality
      NetworkTransportKind.CELLULAR_OR_METERED -> cellularQuality
    }
  }
}

/**
 * Single authoritative entry point used by BOTH playback (PulseMediaDataSource)
 * and downloading (SongDownloadManager / DownloadManager).
 */
class YTPlayerUtils(
  private val innerTubeXResolver: InnerTubeXResolver,
  private val innerTubeCascadeResolver: InnerTubeCascadeResolver,
  private val bravePipeExtractor: BravePipeExtractor,
  private val selfHostedResolver: SelfHostedAudioResolver,
  private val qualityManager: NetworkQualityManager,
  private val pipedFallbackResolver: suspend (trackId: String, quality: String, includeSeeded: Boolean) -> ResolvedStream?,
) {
  @Volatile var engineMode: PlaybackEngineMode = PlaybackEngineMode.AUTO

  fun setSelfHostedConfig(config: SelfHostedServerConfig) {
    selfHostedResolver.setConfig(config)
  }

  fun getSelfHostedConfig(): SelfHostedServerConfig = selfHostedResolver.getConfig()

  suspend fun playerResponseForPlayback(
    track: PulseTrack,
    explicitQuality: String? = null,
    overrideMode: PlaybackEngineMode? = null,
    includeSeeded: Boolean = true,
  ): ResolvedStream? {
    // 1. Local device audio tracks bypass all network resolution
    if (track.id.startsWith("local_") ||
      track.localUri.startsWith("content://") ||
      track.localUri.startsWith("file://")
    ) {
      return null
    }

    val effectiveQuality = qualityManager.effectiveQuality(explicitQuality)
    val activeMode = overrideMode ?: engineMode

    // 2. Self-hosted Jellyfin / Subsonic / Navidrome or explicit streamUrl tracks
    selfHostedResolver.resolveIfApplicable(track, effectiveQuality)?.let { sh ->
      return sh.copy(engineMode = activeMode)
    }

    // 3. Engine cascade strictly determined by PlaybackEngineMode
    val engines: List<suspend () -> ResolvedStream?> =
      when (activeMode) {
        PlaybackEngineMode.AUTO ->
          listOf(
            { innerTubeXResolver.resolve(track.id, effectiveQuality, forcePoToken = false) },
            { innerTubeCascadeResolver.resolve(track.id, effectiveQuality, forcePoToken = false) },
            { bravePipeExtractor.extractStreams(track.id, effectiveQuality) },
            { pipedFallbackResolver(track.id, effectiveQuality, includeSeeded) },
          )
        PlaybackEngineMode.POTOKEN ->
          listOf(
            { innerTubeXResolver.resolve(track.id, effectiveQuality, forcePoToken = true) },
            { innerTubeCascadeResolver.resolve(track.id, effectiveQuality, forcePoToken = true) },
            { bravePipeExtractor.extractStreams(track.id, effectiveQuality) },
            { pipedFallbackResolver(track.id, effectiveQuality, includeSeeded) },
          )
        PlaybackEngineMode.BRAVEPIPE ->
          listOf(
            { bravePipeExtractor.extractStreams(track.id, effectiveQuality) },
            { innerTubeXResolver.resolve(track.id, effectiveQuality, forcePoToken = false) },
            { innerTubeCascadeResolver.resolve(track.id, effectiveQuality, forcePoToken = false) },
            { pipedFallbackResolver(track.id, effectiveQuality, includeSeeded) },
          )
      }

    var fallbackEphemeral: ResolvedStream? = null
    for (step in engines) {
      try {
        val res = step() ?: continue
        val validCandidates =
          StreamFormatRanking.rankCandidates(res.candidates, effectiveQuality)
        if (validCandidates.isNotEmpty()) {
          val resolved =
            res.copy(
              quality = effectiveQuality,
              candidates = validCandidates,
              audioUrls = validCandidates.map { it.url },
              engineMode = activeMode,
            )
          if (resolved.fromApiMirror) {
            return resolved
          } else if (fallbackEphemeral == null) {
            fallbackEphemeral = resolved
          }
        }
      } catch (e: Exception) {
        PulseLog.w(TAG, "Extraction tier error for ${track.id}: ${e.message}")
      }
    }

    return fallbackEphemeral
  }

  companion object {
    private const val TAG = "YTPlayerUtils"
  }
}
