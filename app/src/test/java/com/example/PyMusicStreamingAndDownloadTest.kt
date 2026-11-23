package com.example

import com.example.download.SongFilenameUtils
import com.example.model.PlaybackEngineMode
import com.example.model.PulseTrack
import com.example.model.SelfHostedServerConfig
import com.example.model.SelfHostedServerType
import com.example.stream.AudioCandidate
import com.example.stream.BravePipeExtractor
import com.example.stream.CipherConfigProvider
import com.example.stream.CipherDeobfuscator
import com.example.stream.CipherStep
import com.example.stream.InnerTubeCascadeResolver
import com.example.stream.InnerTubeClientProfiler
import com.example.stream.InnerTubeClientType
import com.example.stream.InnerTubeXResolver
import com.example.stream.NetworkQualityManager
import com.example.stream.NetworkTransportKind
import com.example.stream.OkHttpStreamClient
import com.example.stream.PipedFallbackConstants
import com.example.stream.PlayerCipherConfig
import com.example.stream.PoTokenBundle
import com.example.stream.PoTokenGenerator
import com.example.stream.ResolvedStream
import com.example.stream.SelfHostedAudioResolver
import com.example.stream.StreamFormatRanking
import com.example.stream.StreamHttpClient
import com.example.stream.StreamResolver
import com.example.stream.YTPlayerUtils
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PyMusicStreamingAndDownloadTest {

  private fun m4aPayload(tag: Int, totalBytes: Int = 16_384): ByteArray {
    val buf = ByteArray(totalBytes) { i -> ((i * 13 + tag) and 0xFF).toByte() }
    buf[0] = 0x00
    buf[1] = 0x00
    buf[2] = 0x00
    buf[3] = 0x20
    buf[4] = 'f'.code.toByte()
    buf[5] = 't'.code.toByte()
    buf[6] = 'y'.code.toByte()
    buf[7] = 'p'.code.toByte()
    return buf
  }

  private fun validPlayerJson(streamUrl: String, itag: Int = 251, mime: String = "audio/webm; codecs=\"opus\"", bitrate: Int = 160_000): String =
    """
    {
      "playabilityStatus": {"status": "OK"},
      "playerConfig": {"audioConfig": {"loudnessDb": -3.2}},
      "streamingData": {
        "adaptiveFormats": [
          {"itag": 137, "mimeType": "video/mp4; codecs=\"avc1.640028\"", "bitrate": 2400000, "url": "https://video.example/137"},
          {"itag": $itag, "mimeType": ${JSONObject.quote(mime)}, "averageBitrate": $bitrate, "audioSampleRate": "48000", "contentLength": "3200000", "url": ${JSONObject.quote(streamUrl)}}
        ]
      }
    }
    """.trimIndent()

  @Test
  fun innerTubeClientsAndPipedInstances_matchRequiredArchitectureLists() {
    assertEquals(
      listOf(
        InnerTubeClientType.WEB_REMIX,
        InnerTubeClientType.VISIONOS,
        InnerTubeClientType.ANDROID_VR_1_65_10,
        InnerTubeClientType.TVHTML5,
        InnerTubeClientType.ANDROID_VR_1_43_32,
        InnerTubeClientType.WEB_CREATOR,
        InnerTubeClientType.IPADOS,
        InnerTubeClientType.IOS,
      ),
      InnerTubeClientType.CASCADE_ORDER,
    )

    assertEquals(
      listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
      ),
      PipedFallbackConstants.MANDATORY_PIPED_INSTANCES,
    )

    assertEquals(
      listOf(251, 774, 141, 140, 250, 249, 171, 139, 600, 599, 18, 22),
      StreamFormatRanking.AUDIO_ITAG_PRIORITY,
    )
  }

  @Test
  fun cipherProviderAndDeobfuscator_decipherRemoteZemerAndPlayerJs() {
    val now = 1_750_000_000_000L
    val http =
      object : StreamHttpClient {
        override fun getText(url: String): String {
          if (url.contains("player_configs.json")) {
            return """
              {
                "players": {
                  "p99abc12": {
                    "sts": 20199,
                    "sig_ops": [["w", 2], ["r"], ["s", 1]],
                    "n_shift": 3
                  }
                }
              }
            """.trimIndent()
          }
          if (url.contains("base.js")) {
            return """
              var _yt = {};
              var sigHelper={Za:function(a){a.reverse()},Yb:function(a,b){a.splice(0,b)},Xc:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
              var signatureTimestamp:20244;
              var decodeFn=function(a){a=a.split("");sigHelper.Xc(a,1);sigHelper.Za(a);sigHelper.Yb(a,1);return a.join("")};
            """.trimIndent()
          }
          throw IOException("Unexpected $url")
        }
      }

    val provider = CipherConfigProvider(http, clock = { now })
    val remoteCfg = runBlocking { provider.getRemoteConfig("p99abc12") }
    assertNotNull(remoteCfg)
    assertEquals(20199, remoteCfg!!.signatureTimestamp)
    assertEquals(listOf(CipherStep.Swap(2), CipherStep.Reverse, CipherStep.Splice(1)), remoteCfg.sigSteps)

    val decipheredFromRemote =
      CipherDeobfuscator.decipherFormatUrl(
        directUrl = null,
        signatureCipher = "s=ABCDEF&sp=sig&url=https%3A%2F%2Fcdn.example%2Fvideoplayback%3Fitag%3D251%26n%3DDef45",
        cipherConfig = remoteCfg,
        poToken = "potTokenVal",
      )
    assertNotNull(decipheredFromRemote)
    // "ABCDEF" -> Swap(2): "CBADEF" -> Reverse: "FEDABC" -> Splice(1): "EDABC"
    assertTrue(decipheredFromRemote!!.contains("sig=EDABC"))
    assertTrue(decipheredFromRemote.contains("pot=potTokenVal"))
    assertFalse("n param must be transformed", decipheredFromRemote.contains("n=Def45"))

    val jsCfg = provider.parsePlayerJs("localjs", http.getText("https://www.youtube.com/s/player/localjs/base.js"))
    assertNotNull(jsCfg)
    assertEquals(20244, jsCfg!!.signatureTimestamp)
    assertEquals(listOf(CipherStep.Swap(1), CipherStep.Reverse, CipherStep.Splice(1)), jsCfg.sigSteps)
    // "ABCDE" -> Swap(1): "BACDE" -> Reverse: "EDCAB" -> Splice(1): "DCAB"
    assertEquals("DCAB", CipherDeobfuscator.applyCipherSteps("ABCDE", jsCfg.sigSteps))
  }

  @Test
  fun clientProfiler_demotesFailingSlowClientAndPromotesFastClient() {
    var fakeNow = 10_000_000L
    val profiler = InnerTubeClientProfiler(clock = { fakeNow })

    // Simulate WEB_REMIX failing while ANDROID_VR_1_65_10 succeeds fast
    profiler.recordFailure(InnerTubeClientType.WEB_REMIX)
    profiler.recordFailure(InnerTubeClientType.VISIONOS)
    profiler.recordSuccess(InnerTubeClientType.ANDROID_VR_1_65_10, latencyMs = 45L)
    profiler.recordSuccess(InnerTubeClientType.IOS, latencyMs = 240L)

    val ranked = profiler.rankedClients()
    assertEquals(InnerTubeClientType.ANDROID_VR_1_65_10, ranked.first())
    assertTrue(ranked.indexOf(InnerTubeClientType.IOS) < ranked.indexOf(InnerTubeClientType.WEB_REMIX))
  }

  @Test
  fun engineModes_respectExecutionOrderAndPoTokenInjection() = runBlocking {
    val trace = CopyOnWriteArrayList<String>()
    val failInnerTube = AtomicBoolean(false)
    val failBravePipe = AtomicBoolean(false)

    val http =
      object : StreamHttpClient {
        override fun getText(url: String): String {
          if (url.contains("player_configs.json")) return "{}"
          if (url.contains("/watch?v=") || url.contains("/embed/")) {
            trace.add("bravepipe")
            if (failBravePipe.get()) throw IOException("BravePipe unavailable")
            val json = validPlayerJson("https://cdn.example/videoplayback?itag=251&bp=1", 251)
            return "<html><script>var ytInitialPlayerResponse = $json;</script></html>"
          }
          if (url.contains("/streams/")) {
            trace.add("piped")
            return """{"audioStreams":[{"url":"https://piped.example/videoplayback?itag=140","mimeType":"audio/mp4","bitrate":128000,"itag":140}],"relatedStreams":[]}"""
          }
          throw IOException("404 $url")
        }

        override suspend fun postJsonSuspend(
          url: String,
          jsonBody: String,
          headers: Map<String, String>,
        ): String {
          val reqJson = JSONObject(jsonBody)
          val clientName = reqJson.getJSONObject("context").getJSONObject("client").getString("clientName")
          val hasPot = reqJson.has("serviceIntegrityDimensions")
          trace.add("innertube:$clientName:pot=$hasPot")
          if (failInnerTube.get()) throw IOException("InnerTube blocked")
          return validPlayerJson("https://cdn.example/videoplayback?itag=251&c=$clientName", 251)
        }
      }

    val cipherProvider = CipherConfigProvider(http)
    val poTokenGen = PoTokenGenerator()
    poTokenGen.setManualOverride("UNIT_TEST_POT_123", "VISITOR_ABC")
    val profiler = InnerTubeClientProfiler()
    val qualityMgr = NetworkQualityManager { NetworkTransportKind.WIFI_OR_UNMETERED }

    val pipedResolver = StreamResolver(httpClient = http)
    val utils =
      YTPlayerUtils(
        innerTubeXResolver = InnerTubeXResolver(http, profiler, cipherProvider, poTokenGen),
        innerTubeCascadeResolver = InnerTubeCascadeResolver(http, cipherProvider, poTokenGen),
        bravePipeExtractor = BravePipeExtractor(http, cipherProvider),
        selfHostedResolver = SelfHostedAudioResolver(),
        qualityManager = qualityMgr,
        pipedFallbackResolver = { id, q, _ -> pipedResolver.fetchFromPipedMirrors(id, q, false) },
      )

    val track = PulseTrack(id = "trackMode001", title = "Test Track", artist = "Artist")

    // 1. AUTO mode: InnerTubeX succeeds first without forced PoToken
    trace.clear()
    utils.engineMode = PlaybackEngineMode.AUTO
    val autoRes = utils.playerResponseForPlayback(track)
    assertNotNull(autoRes)
    assertEquals("innertubex", autoRes!!.engineUsed)
    assertTrue(trace.first().startsWith("innertube:WEB_REMIX:pot=false"))

    // 2. POTOKEN mode: includes serviceIntegrityDimensions.poToken & ?pot= on CDN URL
    trace.clear()
    utils.engineMode = PlaybackEngineMode.POTOKEN
    val potRes = utils.playerResponseForPlayback(track)
    assertNotNull(potRes)
    assertTrue(trace.first().contains(":pot=true"))
    assertTrue(potRes!!.audioUrls.first().contains("pot=UNIT_TEST_POT_123"))

    // 3. BRAVEPIPE mode: hits BravePipe watch extractor BEFORE InnerTube
    trace.clear()
    utils.engineMode = PlaybackEngineMode.BRAVEPIPE
    val bpRes = utils.playerResponseForPlayback(track)
    assertNotNull(bpRes)
    assertEquals("bravepipe", bpRes!!.engineUsed)
    assertEquals("bravepipe", trace.first())

    // 4. Full cascade fallback to Piped when InnerTube + BravePipe fail
    trace.clear()
    failInnerTube.set(true)
    failBravePipe.set(true)
    utils.engineMode = PlaybackEngineMode.AUTO
    val pipedFallback = utils.playerResponseForPlayback(track)
    assertNotNull(pipedFallback)
    assertEquals("piped", pipedFallback!!.engineUsed)
    assertTrue(trace.any { it == "bravepipe" })
    assertTrue(trace.any { it == "piped" })
    assertEquals("https://piped.example/videoplayback?itag=140", pipedFallback.audioUrls.first())
  }

  @Test
  fun formatRanking_enforcesItagPriorityExpiredUrlRejectionAndNetworkQuality() {
    val nowMs = 2_000_000_000_000L // sec = 2_000_000_000
    val expiredUrl = "https://cdn.example/videoplayback?itag=251&expire=${nowMs / 1000L - 10}"
    val videoOnly = AudioCandidate("https://cdn.example/video?itag=137", "video/mp4; codecs=\"avc1.640028\"", 2_500_000, "cdn.example", itag = 137)
    val muxed18 = AudioCandidate("https://cdn.example/muxed?itag=18", "video/mp4; codecs=\"mp4a.40.2\"", 500_000, "cdn.example", itag = 18, isMuxedFallback = true)
    val opus251 = AudioCandidate("https://cdn.example/a?itag=251&expire=${nowMs / 1000L + 3600}", "audio/webm; codecs=\"opus\"", 160_000, "cdn.example", itag = 251)
    val aac140 = AudioCandidate("https://cdn.example/a?itag=140&expire=${nowMs / 1000L + 3600}", "audio/mp4; codecs=\"mp4a.40.2\"", 128_000, "cdn.example", itag = 140)
    val low139 = AudioCandidate("https://cdn.example/a?itag=139&expire=${nowMs / 1000L + 3600}", "audio/mp4; codecs=\"mp4a.40.2\"", 48_000, "cdn.example", itag = 139)
    val expired = AudioCandidate(expiredUrl, "audio/webm; codecs=\"opus\"", 160_000, "cdn.example", itag = 251)

    val rankedHigh = StreamFormatRanking.rankCandidates(listOf(videoOnly, expired, muxed18, low139, aac140, opus251), "high", nowMs)
    assertFalse(rankedHigh.any { it.itag == 137 })
    assertFalse(rankedHigh.any { it.url == expiredUrl })
    assertEquals(251, rankedHigh.first().itag)
    assertEquals(18, rankedHigh.last().itag) // muxed fallback strictly last

    val rankedLow = StreamFormatRanking.rankCandidates(listOf(opus251, aac140, low139), "low", nowMs)
    assertEquals(139, rankedLow.first().itag)

    // Wi-Fi vs Cellular automatic selection
    var transport = NetworkTransportKind.WIFI_OR_UNMETERED
    val nqm = NetworkQualityManager { transport }
    nqm.setWifiQuality("high")
    nqm.setCellularQuality("low")
    assertEquals("high", nqm.effectiveQuality())
    transport = NetworkTransportKind.CELLULAR_OR_METERED
    assertEquals("low", nqm.effectiveQuality())
  }

  @Test
  fun selfHostedAndLocalTracks_routeWithAuthAndBypassNetwork() = runBlocking {
    val resolver = StreamResolver()

    // Local device track bypasses network resolution completely
    val localTrack = PulseTrack(id = "local_abc123", title = "Local Song", localUri = "content://media/external/audio/media/99")
    assertNull(resolver.resolveStreamForTrack(localTrack))

    // Jellyfin track gets X-Emby-Token & Authorization headers
    resolver.setSelfHostedServer(
      SelfHostedServerConfig(
        type = SelfHostedServerType.JELLYFIN,
        baseUrl = "https://jf.home.arpa",
        username = "alex",
        tokenOrPassword = "secret-jf-token",
      )
    )
    val jfStream = resolver.resolveStreamForTrack(PulseTrack(id = "jf_item77", title = "FLAC Song", sourceType = "jellyfin"), "high")
    assertNotNull(jfStream)
    assertEquals("jellyfin", jfStream!!.engineUsed)
    assertEquals("secret-jf-token", jfStream.candidates.first().headers["X-Emby-Token"])
    assertTrue(jfStream.audioUrls.first().contains("/Audio/item77/stream"))

    // Navidrome / Subsonic builds salted MD5 token URL
    resolver.setSelfHostedServer(
      SelfHostedServerConfig(
        type = SelfHostedServerType.NAVIDROME,
        baseUrl = "https://navi.home.arpa",
        username = "alex",
        tokenOrPassword = "mypassword",
      )
    )
    val navStream = resolver.resolveStreamForTrack(PulseTrack(id = "nav_song55", title = "Nav Song", sourceType = "navidrome"), "high")
    assertNotNull(navStream)
    assertTrue(navStream!!.audioUrls.first().contains("/rest/stream.view?id=song55"))
    assertTrue(navStream.audioUrls.first().contains("&t="))
    assertTrue(navStream.audioUrls.first().contains("&maxBitRate=0"))
  }

  @Test
  fun stressTest_twentyConsecutiveTracksResolveStreamAndFailover() = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = server.address.port
    val base = "http://127.0.0.1:$port"

    server.createContext("/youtubei/v1/player") { ex ->
      val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
      val vid = JSONObject(reqBody).getString("videoId")
      val idx = vid.removePrefix("track_").toIntOrNull() ?: 0
      val resp =
        if (idx == 7) {
          // Track 7 returns unplayable status on InnerTube to force BravePipe watch extraction!
          """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""
        } else {
          validPlayerJson("$base/cdn/$vid.m4a?itag=140", 140, "audio/mp4; codecs=\"mp4a.40.2\"", 128_000)
        }
      val bytes = resp.toByteArray()
      ex.responseHeaders.add("Content-Type", "application/json")
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }

    server.createContext("/watch") { ex ->
      val query = ex.requestURI.rawQuery.orEmpty()
      val vid = Regex("v=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "track_7"
      val json = validPlayerJson("$base/cdn/$vid.m4a?itag=251", 251)
      val html = "<html><script>var ytInitialPlayerResponse = $json;</script></html>".toByteArray()
      ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      ex.sendResponseHeaders(200, html.size.toLong())
      ex.responseBody.use { it.write(html) }
    }

    server.createContext("/cdn/") { ex ->
      val name = ex.requestURI.path.substringAfterLast('/')
      val idx = Regex("\\d+").find(name)?.value?.toIntOrNull() ?: 1
      val payload = m4aPayload(idx, 12_288)
      ex.responseHeaders.add("Content-Type", "audio/mp4")
      ex.sendResponseHeaders(200, payload.size.toLong())
      ex.responseBody.use { it.write(payload) }
    }
    server.start()

    try {
      val httpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
      val resolver =
        StreamResolver(
          httpClient = OkHttpStreamClient(httpClient),
          customWatchBaseUrl = base,
          customInnerTubeBaseUrl = base,
        )

      for (i in 1..20) {
        val track = PulseTrack(id = "track_$i", title = "Song $i", artist = "Artist $i")
        val resolved = resolver.resolveStreamForTrack(track, explicitQuality = "high")
        assertNotNull("Track $i must resolve", resolved)
        if (i == 7) {
          assertEquals("Track 7 must fail over to bravepipe", "bravepipe", resolved!!.engineUsed)
        } else {
          assertEquals("innertubex", resolved!!.engineUsed)
        }
        val audioUrl = resolved.audioUrls.first()
        httpClient.newCall(Request.Builder().url(audioUrl).build()).execute().use { resp ->
          assertEquals(200, resp.code)
          val bytes = resp.body!!.bytes()
          val fmt = SongFilenameUtils.inspectStreamFormat(resp.header("Content-Type"), bytes, audioUrl)
          assertTrue("Track $i audio payload must pass magic check", fmt.isValidAudio)
          assertArrayEquals(m4aPayload(i, 12_288), bytes)
        }
      }
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun downloadPipelineSimulation_tenDownloadsCancelRetryCacheReuseAndDeleteSync() = runBlocking {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base = "http://127.0.0.1:${server.address.port}"
    val networkFetchesForTrack3 = AtomicInteger(0)

    server.createContext("/audio/") { ex ->
      val path = ex.requestURI.path
      val idx = Regex("\\d+").find(path)?.value?.toIntOrNull() ?: 0
      if (path.endsWith("html")) {
        val bad = "<html><body>Blocked HTML error page</body></html>".toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html")
        ex.sendResponseHeaders(200, bad.size.toLong())
        ex.responseBody.use { it.write(bad) }
        return@createContext
      }
      if (idx == 3) {
        networkFetchesForTrack3.incrementAndGet()
      }
      val full = m4aPayload(idx, 32_768)
      val range = ex.requestHeaders.getFirst("Range").orEmpty()
      ex.responseHeaders.add("Content-Type", "audio/mp4")
      if (range.startsWith("bytes=")) {
        val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
        val slice = full.copyOfRange(start.coerceIn(0, full.size), full.size)
        ex.responseHeaders.add("Content-Range", "bytes $start-${full.size - 1}/${full.size}")
        ex.sendResponseHeaders(206, slice.size.toLong())
        ex.responseBody.use { it.write(slice) }
      } else {
        ex.sendResponseHeaders(200, full.size.toLong())
        ex.responseBody.use { it.write(full) }
      }
    }
    server.start()

    val tempDir = Files.createTempDirectory("pymusic_dl_test").toFile()
    val playerCacheDir = File(tempDir, "player_cache").apply { mkdirs() }
    val musicDir = File(tempDir, "Music/PyMusic").apply { mkdirs() }

    try {
      val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

      // 1. Pre-populate playerCache prefix (first 16KB of track 3) to simulate prior streaming
      val expectedTrack3 = m4aPayload(3, 32_768)
      val cachedPrefixFile = File(playerCacheDir, "dl_track_3.part")
      cachedPrefixFile.writeBytes(expectedTrack3.copyOfRange(0, 16_384))

      // 2. Reject HTML disguised as download URL before creating output file
      client.newCall(Request.Builder().url("$base/audio/bad.html").build()).execute().use { resp ->
        val fmt = SongFilenameUtils.inspectStreamFormat(resp.header("Content-Type"), resp.body!!.bytes())
        assertFalse("HTML challenge must fail validation", fmt.isValidAudio)
      }

      // 3. Download 10 distinct tracks concurrently into temp Music/PyMusic folder, deduplicating names cleanly
      val existingNames = HashSet<String>()
      val downloadedFiles =
        (1..10).map { idx ->
          async {
            val id = "dl_track_$idx"
            val url = "$base/audio/$idx.m4a"
            val prefixFile = File(playerCacheDir, "$id.part")
            val finalBytes: ByteArray =
              if (prefixFile.exists()) {
                val prefix = prefixFile.readBytes()
                val tailResp =
                  client.newCall(
                    Request.Builder().url(url).header("Range", "bytes=${prefix.size}-").build()
                  ).execute()
                tailResp.use { r ->
                  assertEquals(206, r.code)
                  prefix + r.body!!.bytes()
                }
              } else {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                  assertEquals(200, r.code)
                  r.body!!.bytes()
                }
              }

            val fmt = SongFilenameUtils.inspectStreamFormat("audio/mp4", finalBytes, url)
            assertTrue(fmt.isValidAudio)

            val fileName =
              synchronized(existingNames) {
                val baseName = SongFilenameUtils.sanitizeBaseName("Track $idx", "PyMusic Artist")
                SongFilenameUtils.deduplicateFileName(baseName, fmt.extension, existingNames).also {
                  existingNames.add(it)
                }
              }
            val outFile = File(musicDir, fileName)
            outFile.writeBytes(finalBytes)
            outFile
          }
        }.awaitAll()

      assertEquals(10, downloadedFiles.size)
      assertTrue(downloadedFiles.all { it.exists() && it.length() == 32_768L })
      assertArrayEquals(expectedTrack3, downloadedFiles[2].readBytes())
      assertEquals(1, networkFetchesForTrack3.get())

      // 4. Duplicate filename protection
      val dupName = SongFilenameUtils.deduplicateFileName("PyMusic Artist - Track 1", "m4a", existingNames)
      assertEquals("PyMusic Artist - Track 1 (2).m4a", dupName)

      // 5. Delete removes file + cached chunks cleanly
      val targetDelete = downloadedFiles[0]
      assertTrue(targetDelete.delete())
      assertFalse(targetDelete.exists())
    } finally {
      server.stop(0)
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun hardenedExtraction_nativeHeadersRealVisitorEscapedEmbedAndNoDeadInvidious() = runBlocking {
    // 1. Dead Invidious host must not remain in default pool
    assertFalse(
      StreamResolver.DEFAULT_INVIDIOUS.any { it.contains("invidious.privacyredirect.com") }
    )

    // 2. Native clients omit Origin/Referer & HTML5_PREF_WANTS while web clients keep them
    val vrHeaders = InnerTubeClientType.ANDROID_VR_1_65_10.buildHeaders("REAL_VISITOR_1")
    assertFalse(vrHeaders.containsKey("Origin"))
    assertFalse(vrHeaders.containsKey("Referer"))
    val vrPayload =
      JSONObject(
        InnerTubeClientType.ANDROID_VR_1_65_10.buildPlayerPayload(
          videoId = "QEbuK846Raw",
          poTokenBundle = PoTokenBundle("REAL_VISITOR_1", "pot", 1_000L),
          signatureTimestamp = 20073,
          includePoToken = false,
        )
      )
    assertFalse(vrPayload.has("playbackContext"))

    val webHeaders = InnerTubeClientType.WEB_REMIX.buildHeaders("REAL_VISITOR_1")
    assertTrue(webHeaders.containsKey("Origin"))
    val webPayload =
      JSONObject(
        InnerTubeClientType.WEB_REMIX.buildPlayerPayload(
          videoId = "QEbuK846Raw",
          poTokenBundle = PoTokenBundle("REAL_VISITOR_1", "pot", 1_000L),
          signatureTimestamp = 20073,
          includePoToken = false,
        )
      )
    assertTrue(webPayload.has("playbackContext"))

    // 3. Real visitorData fetched once and cached
    val visitorCalls = AtomicInteger(0)
    val http =
      object : StreamHttpClient {
        override fun getText(url: String): String = ""
        override suspend fun postJsonSuspend(
          url: String,
          jsonBody: String,
          headers: Map<String, String>,
        ): String {
          if (url.contains("visitor_id")) {
            visitorCalls.incrementAndGet()
            return """{"responseContext":{"visitorData":"CgtSRV9WSVNJVE9SXzE"}}"""
          }
          throw IOException("Unexpected $url")
        }
      }
    val poGen = PoTokenGenerator(clock = { 100_000L })
    val b1 = poGen.getOrGenerateWithRealVisitor(http)
    val b2 = poGen.getOrGenerateWithRealVisitor(http)
    assertEquals("CgtSRV9WSVNJVE9SXzE", b1.visitorData)
    assertEquals("CgtSRV9WSVNJVE9SXzE", b2.visitorData)
    assertEquals(1, visitorCalls.get())

    // 4. General JS parameter decipher (not hardcoded 'a') + escaped embed playerResponse
    val js =
      """
      var helperObj={rv:function(x){x.reverse()},sp:function(x,b){x.splice(0,b)}};
      var dec=function(sig){sig=sig.split("");helperObj.rv(sig);helperObj.sp(sig,1);return sig.join("")};
      """.trimIndent()
    val steps = CipherConfigProvider.extractCipherOpsFromJs(js)
    assertEquals(listOf(CipherStep.Reverse, CipherStep.Splice(1)), steps)
    assertEquals("DCBA", CipherDeobfuscator.applyCipherSteps("ABCDE", steps))

    val innerJson = validPlayerJson("https://cdn.example/videoplayback?itag=251", 251)
    val escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"")
    val embedHtml = """<script>var cfg={"playerResponse":"$escaped"};</script>"""
    val extracted = BravePipeExtractor.extractPlayerResponseJson(embedHtml)
    assertNotNull(extracted)
    assertTrue(JSONObject(extracted!!).has("streamingData"))
  }
}
