package com.example.stream

import com.example.PulseLog
import com.example.model.PlaybackEngineMode
import com.example.model.PulseTrack
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface StreamHttpClient {
  @Throws(IOException::class)
  fun getText(url: String): String

  suspend fun getTextSuspend(url: String): String = getText(url)

  @Throws(IOException::class)
  fun getTextWithHeaders(url: String, headers: Map<String, String>): String = getText(url)

  suspend fun getTextWithHeadersSuspend(url: String, headers: Map<String, String>): String =
    getTextWithHeaders(url, headers)

  @Throws(IOException::class)
  fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String {
    throw IOException("POST not implemented by StreamHttpClient for $url")
  }

  suspend fun postJsonSuspend(
    url: String,
    jsonBody: String,
    headers: Map<String, String> = emptyMap(),
  ): String = postJson(url, jsonBody, headers)
}

class OkHttpStreamClient(
  val okHttpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
) : StreamHttpClient {
  private fun buildGetRequest(url: String, headers: Map<String, String>): Request {
    val b =
      Request.Builder()
        .url(url)
        .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.5")
        .header("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
    headers.forEach { (k, v) ->
      if (!k.equals("User-Agent", ignoreCase = true) && v.isNotBlank()) {
        b.header(k, v)
      }
    }
    return b.build()
  }

  private fun buildPostRequest(
    url: String,
    jsonBody: String,
    headers: Map<String, String>,
  ): Request {
    val b =
      Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
    headers.forEach { (k, v) ->
      if (!k.equals("User-Agent", ignoreCase = true) &&
        !k.equals("Content-Type", ignoreCase = true) &&
        v.isNotBlank()
      ) {
        b.header(k, v)
      }
    }
    return b.build()
  }

  private fun parseResponseOrThrow(url: String, resp: Response, allowHtml: Boolean): String {
    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from ${PulseLog.redactUrl(url)}")
    val ct = resp.header("Content-Type").orEmpty().lowercase()
    if (!allowHtml && (ct.contains("text/html") || ct.contains("application/xhtml"))) {
      throw IOException("HTML response ($ct) from ${PulseLog.redactUrl(url)}")
    }
    val body = resp.body?.string().orEmpty()
    if (body.isBlank()) throw IOException("Empty response from ${PulseLog.redactUrl(url)}")
    if (!allowHtml) {
      val trimmed = body.trimStart()
      if (trimmed.startsWith("<!doctype", ignoreCase = true) ||
        trimmed.startsWith("<html", ignoreCase = true)
      ) {
        throw IOException("HTML challenge payload from ${PulseLog.redactUrl(url)}")
      }
    }
    return body
  }

  override fun getText(url: String): String = getTextWithHeaders(url, emptyMap())

  override suspend fun getTextSuspend(url: String): String =
    getTextWithHeadersSuspend(url, emptyMap())

  override fun getTextWithHeaders(url: String, headers: Map<String, String>): String {
    val allowHtml = headers[HEADER_ALLOW_HTML] == "1"
    val cleanHeaders = headers.filterKeys { it != HEADER_ALLOW_HTML }
    okHttpClient.newCall(buildGetRequest(url, cleanHeaders)).execute().use { resp ->
      return parseResponseOrThrow(url, resp, allowHtml)
    }
  }

  override suspend fun getTextWithHeadersSuspend(
    url: String,
    headers: Map<String, String>,
  ): String {
    val allowHtml = headers[HEADER_ALLOW_HTML] == "1"
    val cleanHeaders = headers.filterKeys { it != HEADER_ALLOW_HTML }
    return enqueueSuspend(buildGetRequest(url, cleanHeaders), url, allowHtml)
  }

  override fun postJson(url: String, jsonBody: String, headers: Map<String, String>): String {
    okHttpClient.newCall(buildPostRequest(url, jsonBody, headers)).execute().use { resp ->
      return parseResponseOrThrow(url, resp, allowHtml = false)
    }
  }

  override suspend fun postJsonSuspend(
    url: String,
    jsonBody: String,
    headers: Map<String, String>,
  ): String = enqueueSuspend(buildPostRequest(url, jsonBody, headers), url, allowHtml = false)

  private suspend fun enqueueSuspend(req: Request, url: String, allowHtml: Boolean): String =
    suspendCancellableCoroutine { cont ->
      val call = okHttpClient.newCall(req)
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            try {
              response.use { r ->
                val text = parseResponseOrThrow(url, r, allowHtml)
                if (cont.isActive) cont.resume(text)
              }
            } catch (e: IOException) {
              if (cont.isActive) cont.resumeWithException(e)
            }
          }
        }
      )
    }

  companion object {
    const val HEADER_ALLOW_HTML = "X-Pulse-Allow-Html"
    private const val DEFAULT_USER_AGENT = "PulsePlayer/1.0 (Linux; Android)"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}

data class AudioCandidate(
  val url: String,
  val mimeType: String,
  val bitrate: Int,
  val sourceHost: String,
  val apiMirrorHost: String = sourceHost,
  val itag: Int = StreamFormatRanking.extractItagFromUrl(url),
  val engineSource: String = "piped",
  val clientName: String = "",
  val headers: Map<String, String> = emptyMap(),
  val contentLength: Long = -1L,
  val codecs: String = "",
  val sampleRate: Int = 0,
  val isMuxedFallback: Boolean = itag in StreamFormatRanking.MUXED_FALLBACK_ITAGS,
)

data class ResolvedStream(
  val trackId: String,
  val quality: String,
  val audioUrls: List<String>,
  val candidates: List<AudioCandidate> = emptyList(),
  val relatedTracks: List<PulseTrack> = emptyList(),
  val resolvedAtMs: Long = System.currentTimeMillis(),
  val expiresAtMs: Long = System.currentTimeMillis() + StreamResolver.STREAM_CACHE_TTL_MS,
  val fromApiMirror: Boolean = true,
  val engineUsed: String = "piped",
  val clientUsed: String = "",
  val engineMode: PlaybackEngineMode = PlaybackEngineMode.AUTO,
  val loudnessDb: Double? = null,
) {
  fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

object StreamFormatRanking {
  // Ordered audio-only preference plus progressive muxed fallbacks (18, 22) as last resort
  val AUDIO_ITAG_PRIORITY: List<Int> =
    listOf(251, 774, 141, 140, 250, 249, 171, 139, 600, 599, 18, 22)

  val MUXED_FALLBACK_ITAGS: Set<Int> = setOf(18, 22)

  private val ITAG_PRIORITY_INDEX: Map<Int, Int> =
    AUDIO_ITAG_PRIORITY.withIndex().associate { (idx, itag) -> itag to idx }

  private val ITAG_QUERY_REGEX = Regex("[?&]itag=(\\d+)")
  private val EXPIRE_QUERY_REGEX = Regex("[?&]expire=(\\d+)")

  fun extractItagFromUrl(url: String): Int =
    ITAG_QUERY_REGEX.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

  fun isUrlExpired(url: String, nowMs: Long = System.currentTimeMillis(), safetyBufferSec: Long = 30L): Boolean {
    val expSec = EXPIRE_QUERY_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
    return expSec > 0L && (nowMs / 1000L) + safetyBufferSec >= expSec
  }

  fun itagPriorityIndex(itag: Int): Int =
    ITAG_PRIORITY_INDEX[itag] ?: (AUDIO_ITAG_PRIORITY.size + 10)

  fun isAudioPlayableCandidate(
    candidate: AudioCandidate,
    allowMuxedFallback: Boolean = true,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    if (!StreamResolver.isValidStreamUrl(candidate.url)) return false
    if (StreamResolver.isPlaylistOrManifestUrl(candidate.url, candidate.mimeType)) return false
    if (isUrlExpired(candidate.url, nowMs)) return false

    val mime = candidate.mimeType.lowercase()
    val effectiveItag = if (candidate.itag > 0) candidate.itag else extractItagFromUrl(candidate.url)
    val isMuxed = effectiveItag in MUXED_FALLBACK_ITAGS || candidate.isMuxedFallback

    if (mime.startsWith("video/") && (!allowMuxedFallback || !isMuxed)) {
      return false
    }
    if (mime.isBlank() || mime.startsWith("audio/") || mime.contains("mp4a") || mime.contains("opus")) {
      return true
    }
    return allowMuxedFallback && isMuxed
  }

  fun rankCandidates(
    candidates: List<AudioCandidate>,
    quality: String,
    nowMs: Long = System.currentTimeMillis(),
  ): List<AudioCandidate> {
    val normalized =
      candidates
        .map { cand ->
          val inferredItag = if (cand.itag > 0) cand.itag else extractItagFromUrl(cand.url)
          cand.copy(
            itag = inferredItag,
            isMuxedFallback = cand.isMuxedFallback || inferredItag in MUXED_FALLBACK_ITAGS,
          )
        }
        .filter { isAudioPlayableCandidate(it, allowMuxedFallback = true, nowMs = nowMs) }
        .distinctBy { it.url }

    if (normalized.isEmpty()) return emptyList()

    val q = StreamResolver.normalizeQuality(quality)
    val targetBitrate =
      when (q) {
        "low" -> 50_000
        "high" -> 320_000
        else -> 128_000
      }

    // Prefer pure audio-only over muxed 18/22 whenever audio-only exists.
    return normalized.sortedWith(
      compareBy<AudioCandidate> { it.isMuxedFallback }
        .thenBy { cand ->
          when (q) {
            "high" -> {
              if (cand.itag > 0) itagPriorityIndex(cand.itag)
              else 50 + containerPreference(cand.mimeType)
            }
            "low" -> {
              when (cand.itag) {
                139 -> 0
                599 -> 1
                600 -> 2
                249 -> 3
                250 -> 4
                140 -> 5
                171 -> 6
                251 -> 7
                774 -> 8
                141 -> 9
                else -> if (cand.itag > 0) 20 + itagPriorityIndex(cand.itag) else 40 + containerPreference(cand.mimeType)
              }
            }
            else -> { // "normal"
              when (cand.itag) {
                140 -> 0
                250 -> 1
                251 -> 2
                774 -> 3
                141 -> 4
                249 -> 5
                171 -> 6
                139 -> 7
                600 -> 8
                599 -> 9
                else -> if (cand.itag > 0) 20 + itagPriorityIndex(cand.itag) else 40 + containerPreference(cand.mimeType)
              }
            }
          }
        }
        .thenBy { cand ->
          if (q == "high" && cand.itag <= 0) {
            containerPreference(cand.mimeType)
          } else {
            0
          }
        }
        .thenBy { cand ->
          if (q == "high" && cand.itag > 0) {
            -cand.bitrate
          } else {
            kotlin.math.abs((if (cand.bitrate > 0) cand.bitrate else 128_000) - targetBitrate)
          }
        }
        .thenByDescending { it.bitrate }
    )
  }

  private fun containerPreference(mimeType: String): Int =
    when {
      mimeType.contains("mp4", ignoreCase = true) ||
        mimeType.contains("m4a", ignoreCase = true) ||
        mimeType.contains("aac", ignoreCase = true) -> 0
      mimeType.contains("webm", ignoreCase = true) ||
        mimeType.contains("opus", ignoreCase = true) ||
        mimeType.contains("ogg", ignoreCase = true) -> 1
      else -> 2
    }
}
