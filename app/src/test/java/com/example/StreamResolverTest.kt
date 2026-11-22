package com.example

import com.example.stream.StreamHttpClient
import com.example.stream.StreamResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class StreamResolverTest {

  private val samplePipedJson =
    """
    {
      "audioStreams": [
        {"url": "https://cdn.example/audio-opus-160.webm", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000},
        {"url": "https://cdn.example/audio-m4a-48.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 48000},
        {"url": "https://cdn.example/audio-m4a-128.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000},
        {"url": "https://cdn.example/audio-m4a-256.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 256000}
      ],
      "relatedStreams": [
        {
          "type": "stream",
          "url": "/watch?v=abc12345678",
          "title": "Next Up Track",
          "uploaderName": "Artist - Topic",
          "duration": 210,
          "thumbnail": "https://lh3.googleusercontent.com/test=w60-h60"
        },
        {
          "type": "stream",
          "url": "/watch?v=short000001",
          "title": "Too Short Clip",
          "uploaderName": "Someone",
          "duration": 20
        }
      ]
    }
    """.trimIndent()

  private val sampleInvidiousJson =
    """
    {
      "adaptiveFormats": [
        {"url": "https://inv.example/video-1080.mp4", "type": "video/mp4; codecs=\"avc1.640028\"", "bitrate": "2200000"},
        {"url": "https://inv.example/audio-128.m4a", "type": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": "128000"},
        {"url": "https://inv.example/audio-50.opus", "type": "audio/webm; codecs=\"opus\"", "bitrate": "50000"}
      ],
      "recommendedVideos": []
    }
    """.trimIndent()

  @Test
  fun qualityRanking_selectsPreferredBitrateAndPrioritizesAudioOnly() = runBlocking {
    val resolver =
      StreamResolver(
        httpClient =
          object : StreamHttpClient {
            override fun getText(url: String): String = samplePipedJson
          }
      )

    val high = resolver.parseEndpointResponse(samplePipedJson, "https://piped.test", "high")
    assertEquals("https://cdn.example/audio-m4a-256.m4a", high.urls.first())

    val low = resolver.parseEndpointResponse(samplePipedJson, "https://piped.test", "low")
    assertEquals("https://cdn.example/audio-m4a-48.m4a", low.urls.first())

    val normal = resolver.parseEndpointResponse(samplePipedJson, "https://piped.test", "normal")
    assertEquals("https://cdn.example/audio-m4a-128.m4a", normal.urls.first())

    // Filters related tracks outside 60..600s and strips "- Topic"
    assertEquals(1, normal.related.size)
    assertEquals("abc12345678", normal.related[0].id)
    assertEquals("Artist", normal.related[0].artist)
    assertEquals(1, normal.related[0].ms)
  }

  @Test
  fun parseInvidious_excludesVideoStreams() {
    val resolver =
      StreamResolver(
        httpClient =
          object : StreamHttpClient {
            override fun getText(url: String): String = sampleInvidiousJson
          }
      )
    val parsed = resolver.parseEndpointResponse(sampleInvidiousJson, "https://inv.test", "high")
    assertEquals(2, parsed.urls.size)
    assertTrue(parsed.urls.none { it.contains("video-1080") })
    assertEquals("https://inv.example/audio-128.m4a", parsed.urls.first())
  }

  @Test
  fun cacheRespectsTtlExpiryAndInvalidate() = runBlocking {
    var fakeClockMs = 1_000_000L
    val calls = AtomicInteger(0)
    val resolver =
      StreamResolver(
        httpClient =
          object : StreamHttpClient {
            override fun getText(url: String): String {
              if (url.contains("piped-instances") || url.contains("invidious.io")) {
                throw IOException("skip discovery in unit test")
              }
              calls.incrementAndGet()
              return samplePipedJson
            }
          },
        clock = { fakeClockMs },
      )

    val first = resolver.resolveStream("dQw4w9WgXcQ", "normal")
    assertNotNull(first)
    val callsAfterFirst = calls.get()
    assertTrue(callsAfterFirst > 0)

    // Subsequent call before 18m TTL uses memory cache with zero upstream calls
    fakeClockMs += 5 * 60_000L
    val cached = resolver.resolveStream("dQw4w9WgXcQ", "normal")
    assertNotNull(cached)
    assertEquals(callsAfterFirst, calls.get())

    // Advancing past 18m TTL expires cached entry
    fakeClockMs += StreamResolver.STREAM_CACHE_TTL_MS
    assertNull(resolver.getCachedStream("dQw4w9WgXcQ", "normal"))

    // Re-resolve works after expiry, and invalidate clears it again
    val refreshed = resolver.resolveStream("dQw4w9WgXcQ", "normal")
    assertNotNull(refreshed)
    assertTrue(calls.get() > callsAfterFirst)

    resolver.invalidate("dQw4w9WgXcQ")
    assertNull(resolver.getCachedStream("dQw4w9WgXcQ", "normal"))
  }

  @Test
  fun concurrentResolvesForSameTrackDeduplicateUpstreamCalls() = runBlocking {
    val streamCalls = AtomicInteger(0)
    val resolver =
      StreamResolver(
        httpClient =
          object : StreamHttpClient {
            override fun getText(url: String): String {
              if (url.contains("piped-instances") || url.contains("invidious.io")) {
                throw IOException("skip discovery")
              }
              if (url.contains("/streams/")) streamCalls.incrementAndGet()
              Thread.sleep(40)
              return samplePipedJson
            }
          }
      )

    val d1 = async { resolver.resolveStream("track999999", "high") }
    val d2 = async { resolver.resolveStream("track999999", "high") }
    val r1 = d1.await()
    val r2 = d2.await()
    assertNotNull(r1)
    assertEquals(r1?.audioUrls, r2?.audioUrls)
    // Only one batch of mirror queries fired for both concurrent callers (3 default Piped + 3 default Invidious <= 6)
    assertTrue(streamCalls.get() <= StreamResolver.DEFAULT_PIPED.size)
  }

  @Test
  fun failoverRecoversWhenCustomOrFirstMirrorsFail() = runBlocking {
    val resolver =
      StreamResolver(
        httpClient =
          object : StreamHttpClient {
            override fun getText(url: String): String {
              if (url.startsWith("https://broken.example")) throw IOException("502 Bad Gateway")
              if (url.contains("pipedapi.kavin.rocks")) throw IOException("Timeout")
              if (url.contains("/streams/")) return samplePipedJson
              throw IOException("Unused endpoint $url")
            }
          }
      )
    resolver.setCustomServer("https://broken.example")
    val resolved = resolver.resolveStream("failoverTrack1", "normal")
    assertNotNull(resolved)
    assertTrue(resolved!!.audioUrls.contains("https://cdn.example/audio-m4a-128.m4a"))
  }
}
