package com.example.stream

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class InnerTubeClientType(
  val profileId: String,
  val clientName: String,
  val clientVersion: String,
  val clientHeaderId: String,
  val userAgent: String,
  val originUrl: String,
  val deviceMake: String? = null,
  val deviceModel: String? = null,
  val osName: String? = null,
  val osVersion: String? = null,
  val androidSdkVersion: Int? = null,
  val requiresCipherOrSts: Boolean = false,
  val supportsPoToken: Boolean = true,
  val isEmbeddedContext: Boolean = false,
) {
  WEB_REMIX(
    profileId = "WEB_REMIX",
    clientName = "WEB_REMIX",
    clientVersion = "1.20250310.01.00",
    clientHeaderId = "67",
    userAgent =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    originUrl = "https://music.youtube.com",
    requiresCipherOrSts = true,
    supportsPoToken = true,
  ),
  VISIONOS(
    profileId = "VISIONOS",
    clientName = "VISIONOS",
    clientVersion = "0.1",
    clientHeaderId = "101",
    userAgent =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
    originUrl = "https://www.youtube.com",
    deviceMake = "Apple",
    deviceModel = "RealityDevice14,1",
    osName = "visionOS",
    osVersion = "2.3",
    requiresCipherOrSts = false,
    supportsPoToken = true,
  ),
  ANDROID_VR_1_65_10(
    profileId = "ANDROID_VR_1_65_10",
    clientName = "ANDROID_VR",
    clientVersion = "1.65.10",
    clientHeaderId = "28",
    userAgent =
      "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    originUrl = "https://www.youtube.com",
    deviceMake = "Oculus",
    deviceModel = "Quest 3",
    osName = "Android",
    osVersion = "12L",
    androidSdkVersion = 32,
    requiresCipherOrSts = false,
    supportsPoToken = true,
  ),
  TVHTML5(
    profileId = "TVHTML5",
    clientName = "TVHTML5",
    clientVersion = "7.20250310.17.00",
    clientHeaderId = "7",
    userAgent =
      "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
    originUrl = "https://www.youtube.com",
    requiresCipherOrSts = true,
    supportsPoToken = true,
    isEmbeddedContext = true,
  ),
  ANDROID_VR_1_43_32(
    profileId = "ANDROID_VR_1_43_32",
    clientName = "ANDROID_VR",
    clientVersion = "1.43.32",
    clientHeaderId = "28",
    userAgent =
      "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; Quest 2 Build/SQ3A.220605.009.A1) gzip",
    originUrl = "https://www.youtube.com",
    deviceMake = "Oculus",
    deviceModel = "Quest 2",
    osName = "Android",
    osVersion = "12",
    androidSdkVersion = 32,
    requiresCipherOrSts = false,
    supportsPoToken = true,
  ),
  WEB_CREATOR(
    profileId = "WEB_CREATOR",
    clientName = "WEB_CREATOR",
    clientVersion = "1.20250310.00.00",
    clientHeaderId = "62",
    userAgent =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
    originUrl = "https://studio.youtube.com",
    requiresCipherOrSts = true,
    supportsPoToken = true,
  ),
  IPADOS(
    profileId = "IPADOS",
    clientName = "IOS",
    clientVersion = "20.10.4",
    clientHeaderId = "5",
    userAgent =
      "com.google.ios.youtube/20.10.4 (iPad14,5; U; CPU OS 18_3 like Mac OS X)",
    originUrl = "https://www.youtube.com",
    deviceMake = "Apple",
    deviceModel = "iPad14,5",
    osName = "iPadOS",
    osVersion = "18.3.2.22D82",
    requiresCipherOrSts = false,
    supportsPoToken = true,
  ),
  IOS(
    profileId = "IOS",
    clientName = "IOS",
    clientVersion = "20.10.4",
    clientHeaderId = "5",
    userAgent =
      "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
    originUrl = "https://www.youtube.com",
    deviceMake = "Apple",
    deviceModel = "iPhone16,2",
    osName = "iOS",
    osVersion = "18.3.2.22D82",
    requiresCipherOrSts = false,
    supportsPoToken = true,
  );

  fun playerEndpointUrl(): String =
    when (this) {
      WEB_REMIX -> "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
      else -> "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    }

  fun buildHeaders(visitorData: String): Map<String, String> =
    linkedMapOf(
      "User-Agent" to userAgent,
      "Origin" to originUrl,
      "Referer" to "$originUrl/",
      "X-YouTube-Client-Name" to clientHeaderId,
      "X-YouTube-Client-Version" to clientVersion,
      "X-Goog-Visitor-Id" to visitorData,
    )

  fun buildPlayerPayload(
    videoId: String,
    poTokenBundle: PoTokenBundle,
    signatureTimestamp: Int,
    includePoToken: Boolean,
  ): String {
    val clientObj =
      JSONObject().apply {
        put("clientName", clientName)
        put("clientVersion", clientVersion)
        put("hl", "en")
        put("gl", "US")
        put("visitorData", poTokenBundle.visitorData)
        deviceMake?.let { put("deviceMake", it) }
        deviceModel?.let { put("deviceModel", it) }
        osName?.let { put("osName", it) }
        osVersion?.let { put("osVersion", it) }
        androidSdkVersion?.let { put("androidSdkVersion", it) }
      }

    val contextObj =
      JSONObject().apply {
        put("client", clientObj)
        if (isEmbeddedContext) {
          put(
            "thirdParty",
            JSONObject().put("embedUrl", "https://www.youtube.com/watch?v=$videoId"),
          )
        }
      }

    val root =
      JSONObject().apply {
        put("context", contextObj)
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        put(
          "playbackContext",
          JSONObject().put(
            "contentPlaybackContext",
            JSONObject().apply {
              put("signatureTimestamp", signatureTimestamp)
              put("html5Preference", "HTML5_PREF_WANTS")
            },
          ),
        )
        if (includePoToken && supportsPoToken) {
          put(
            "serviceIntegrityDimensions",
            JSONObject().put("poToken", poTokenBundle.poToken),
          )
        }
      }
    return root.toString()
  }

  companion object {
    val CASCADE_ORDER: List<InnerTubeClientType> =
      listOf(
        WEB_REMIX,
        VISIONOS,
        ANDROID_VR_1_65_10,
        TVHTML5,
        ANDROID_VR_1_43_32,
        WEB_CREATOR,
        IPADOS,
        IOS,
      )
  }
}

class InnerTubeClientProfiler(
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  data class BenchmarkStats(
    var successes: Int = 0,
    var failures: Int = 0,
    var consecutiveFailures: Int = 0,
    var ewmaLatencyMs: Double = 350.0,
    var lastFailureMs: Long = 0L,
  ) {
    fun score(nowMs: Long): Double {
      val cooldownPenalty =
        if (consecutiveFailures > 0 && nowMs - lastFailureMs < COOLDOWN_WINDOW_MS) {
          consecutiveFailures * 50.0
        } else {
          0.0
        }
      val total = successes + failures
      val reliability = if (total == 0) 0.5 else successes.toDouble() / total
      val latencyFactor = (ewmaLatencyMs / 250.0).coerceIn(0.2, 8.0)
      return (reliability * 100.0) - latencyFactor * 5.0 - failures * 8.0 - cooldownPenalty
    }

    companion object {
      const val COOLDOWN_WINDOW_MS = 120_000L
    }
  }

  private val stats = ConcurrentHashMap<InnerTubeClientType, BenchmarkStats>()

  fun recordSuccess(client: InnerTubeClientType, latencyMs: Long) {
    val entry = stats.getOrPut(client) { BenchmarkStats() }
    synchronized(entry) {
      entry.successes++
      entry.consecutiveFailures = 0
      val boundedLatency = latencyMs.coerceIn(10L, 10_000L).toDouble()
      entry.ewmaLatencyMs =
        if (entry.successes == 1) boundedLatency
        else (entry.ewmaLatencyMs * 0.7) + (boundedLatency * 0.3)
    }
  }

  fun recordFailure(client: InnerTubeClientType) {
    val entry = stats.getOrPut(client) { BenchmarkStats() }
    synchronized(entry) {
      entry.failures++
      entry.consecutiveFailures++
      entry.lastFailureMs = clock()
    }
  }

  fun rankedClients(
    candidates: List<InnerTubeClientType> = InnerTubeClientType.CASCADE_ORDER,
  ): List<InnerTubeClientType> {
    val now = clock()
    return candidates
      .withIndex()
      .sortedWith(
        compareByDescending<IndexedValue<InnerTubeClientType>> { iv ->
          stats[iv.value]?.score(now) ?: 0.0
        }.thenBy { it.index }
      )
      .map { it.value }
  }

  fun snapshotStats(): Map<InnerTubeClientType, Pair<Int, Double>> =
    stats.mapValues { (_, st) -> synchronized(st) { st.successes to st.ewmaLatencyMs } }
}
