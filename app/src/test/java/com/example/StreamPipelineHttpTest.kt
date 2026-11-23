package com.example

import com.example.download.SongFilenameUtils
import com.example.stream.AudioCandidate
import com.example.stream.OkHttpStreamClient
import com.example.stream.StreamResolver
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamPipelineHttpTest {

  @Test
  fun okHttpStreamClient_rejectsHtmlChallengeEvenOnHttp200() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/streams/test1") { ex ->
      val html = "<!DOCTYPE html><html><body>Cloudflare check</body></html>".toByteArray()
      ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      ex.sendResponseHeaders(200, html.size.toLong())
      ex.responseBody.use { it.write(html) }
    }
    server.start()
    try {
      val client = OkHttpStreamClient()
      val base = "http://127.0.0.1:${server.address.port}"
      var thrown: IOException? = null
      try {
        client.getText("$base/streams/test1")
      } catch (e: IOException) {
        thrown = e
      }
      assertNotNull("Expected HTML HTTP 200 payload to be rejected", thrown)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun concurrentMirrorRacing_fastMirrorWinsWithoutWaitingOnHungMirror() = runBlocking {
    val fastServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val slowServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fastServer.createContext("/streams/raceTrack01") { ex ->
      val body =
        """{"audioStreams":[{"url":"http://127.0.0.1:${fastServer.address.port}/cdn/audio.m4a","mimeType":"audio/mp4","bitrate":128000}],"relatedStreams":[]}"""
          .toByteArray()
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, body.size.toLong())
      ex.responseBody.use { it.write(body) }
    }
    slowServer.createContext("/streams/raceTrack01") { ex ->
      try {
        Thread.sleep(5_000)
      } catch (_: InterruptedException) {}
      val body = "{}".toByteArray()
      ex.sendResponseHeaders(504, body.size.toLong())
      ex.responseBody.use { it.write(body) }
    }
    fastServer.start()
    slowServer.start()

    try {
      val slowUrl = "http://127.0.0.1:${slowServer.address.port}"
      val fastUrl = "http://127.0.0.1:${fastServer.address.port}"
      val resolver =
        StreamResolver(
          httpClient = OkHttpStreamClient(OkHttpClient.Builder().readTimeout(6, TimeUnit.SECONDS).build())
        )
      // Slow custom mirror runs first in list while fast discovered mirror runs concurrently
      resolver.setCustomServer(slowUrl)
      resolver.setDiscoveredMirrors(listOf(fastUrl), emptyList())

      val startMs = System.currentTimeMillis()
      val fastResolved = resolver.resolveStream("raceTrack01", "normal")
      val elapsedMs = System.currentTimeMillis() - startMs
      assertNotNull(fastResolved)
      assertTrue("Fast mirror must resolve well under 3s without waiting on hung 5s mirror (was ${elapsedMs}ms)", elapsedMs < 3_000L)
      assertTrue(
        fastResolved!!.audioUrls.any { it == "$fastUrl/cdn/audio.m4a" }
      )
    } finally {
      fastServer.stop(0)
      slowServer.stop(0)
    }
  }

  @Test
  fun streamValidation_rejectsWatchThumbnailsAndHlsManifestsAndResolvesRelativeUrls() {
    val resolver = StreamResolver()
    val json =
      """
      {
        "audioStreams": [
          {"url": "https://www.youtube.com/watch?v=abc12345678", "mimeType": "audio/mp4", "bitrate": 128000},
          {"url": "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg", "mimeType": "audio/mp4", "bitrate": 128000},
          {"url": "https://cdn.example/playlist.m3u8", "mimeType": "application/vnd.apple.mpegurl", "bitrate": 128000},
          {"url": "/videoplayback?id=abc12345678&itag=140", "mimeType": "audio/mp4", "bitrate": 128000}
        ],
        "relatedStreams": []
      }
      """.trimIndent()
    val parsed = resolver.parseEndpointResponse(json, "https://inv.example", "normal")
    assertEquals(1, parsed.urls.size)
    assertEquals("https://inv.example/videoplayback?id=abc12345678&itag=140", parsed.urls.first())

    assertFalse(StreamResolver.isValidStreamUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    assertFalse(StreamResolver.isValidStreamUrl("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg"))
    assertFalse(StreamResolver.isValidStreamUrl(""))
  }

  @Test
  fun okHttpWithInMemoryCookieJar_handlesRedirectsAndRangeRequestsAndExpiredRefresh() {
    val hits = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val m4aPayload =
      byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
        ByteArray(56) { (it + 1).toByte() }

    server.createContext("/expired") { ex ->
      val msg = "Expired".toByteArray()
      ex.sendResponseHeaders(403, msg.size.toLong())
      ex.responseBody.use { it.write(msg) }
    }
    server.createContext("/redirect") { ex ->
      ex.responseHeaders.add("Set-Cookie", "pulse_tok=valid123; Path=/")
      ex.responseHeaders.add("Location", "/audio")
      ex.sendResponseHeaders(302, -1)
      ex.close()
    }
    server.createContext("/audio") { ex ->
      hits.incrementAndGet()
      val cookie = ex.requestHeaders.getFirst("Cookie").orEmpty()
      val range = ex.requestHeaders.getFirst("Range").orEmpty()
      if (!cookie.contains("pulse_tok=valid123")) {
        val err = "Missing session cookie".toByteArray()
        ex.sendResponseHeaders(403, err.size.toLong())
        ex.responseBody.use { it.write(err) }
        return@createContext
      }
      ex.responseHeaders.add("Content-Type", "audio/mp4")
      if (range.startsWith("bytes=8-")) {
        val slice = m4aPayload.copyOfRange(8, m4aPayload.size)
        ex.responseHeaders.add("Content-Range", "bytes 8-${m4aPayload.size - 1}/${m4aPayload.size}")
        ex.sendResponseHeaders(206, slice.size.toLong())
        ex.responseBody.use { it.write(slice) }
      } else {
        ex.sendResponseHeaders(200, m4aPayload.size.toLong())
        ex.responseBody.use { it.write(m4aPayload) }
      }
    }
    server.start()

    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val client =
        OkHttpClient.Builder()
          .cookieJar(InMemoryCookieJar())
          .followRedirects(true)
          .build()

      // 1. Expired endpoint returns 403
      client.newCall(Request.Builder().url("$base/expired").build()).execute().use { resp ->
        assertEquals(403, resp.code)
      }

      // 2. Redirect sets cookie and reaches /audio with valid M4A bytes
      client.newCall(Request.Builder().url("$base/redirect").build()).execute().use { resp ->
        assertEquals(200, resp.code)
        val bytes = resp.body!!.bytes()
        val info = SongFilenameUtils.inspectStreamFormat(resp.header("Content-Type"), bytes)
        assertTrue(info.isValidAudio)
        assertEquals("m4a", info.extension)
      }

      // 3. Subsequent range request keeps cookie and gets 206 partial slice
      client.newCall(Request.Builder().url("$base/audio").header("Range", "bytes=8-").build()).execute().use { resp ->
        assertEquals(206, resp.code)
        assertEquals(m4aPayload.size - 8, resp.body!!.bytes().size)
      }
      assertTrue(hits.get() >= 2)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun failedApiMirrors_doNotPollutePositiveTtlCacheWithFallbackUrls() = runBlocking {
    val resolver =
      StreamResolver(
        httpClient =
          object : com.example.stream.StreamHttpClient {
            override fun getText(url: String): String {
              throw IOException("Network disconnected")
            }
          }
      )
    val res = resolver.resolveStream("offlineTrack1", "normal")
    assertNotNull(res) // Returns ephemeral /latest_version fallbacks to attempt
    assertFalse(res!!.fromApiMirror)
    assertNull("Fallback-only result must not be cached as positive API hit", resolver.getCachedStream("offlineTrack1", "normal"))
  }

  @Test
  fun candidateReporting_penalizesBothCdnAndOriginApiMirrorHost() {
    val resolver = StreamResolver()
    val cand =
      AudioCandidate(
        url = "https://cdn-bad.example/videoplayback?id=1",
        mimeType = "audio/mp4",
        bitrate = 128_000,
        sourceHost = "cdn-bad.example",
        apiMirrorHost = "https://piped-bad.example",
      )
    resolver.reportCandidateResult(cand, false)
    resolver.reportCandidateResult(cand, true)
    assertEquals("piped-bad.example", StreamResolver.extractHostKey(cand.apiMirrorHost))
  }
}
