package com.example

import com.example.bridge.PulseJsBridge
import com.example.model.DownloadProgressItem
import com.example.model.DownloadStatus
import com.example.model.DownloadedSongRecord
import com.example.model.PlaybackSnapshot
import com.example.model.PulseTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseModelsAndBridgeTest {

  @Test
  fun pulseTrack_jsonRoundTripAndMalformedGuards() {
    val input =
      """
      [
        {"id":"vid123","title":"Midnight City","artist":"M83","duration":243,"thumb":"https://img/a.jpg","ms":true,"auto":1},
        {"id":"","title":"Missing ID"},
        {"title":"No ID Field"}
      ]
      """.trimIndent()
    val parsed = PulseTrack.listFromJsonArray(input)
    assertEquals(1, parsed.size)
    assertEquals("vid123", parsed[0].id)
    assertEquals("Midnight City", parsed[0].title)
    assertEquals("M83", parsed[0].artist)
    assertEquals(243, parsed[0].durationSec)
    assertEquals(1, parsed[0].ms)
    assertEquals(1, parsed[0].auto)

    val serialized = PulseTrack.listToJsonArray(parsed).toString()
    val reParsed = PulseTrack.listFromJsonArray(serialized)
    assertEquals(parsed, reParsed)

    assertTrue(PulseTrack.listFromJsonArray("{not valid json").isEmpty())
    assertTrue(PulseTrack.listFromJsonArray(null).isEmpty())
    assertNull(PulseTrack.fromJson(null))
  }

  @Test
  fun playbackAndDownloadModels_serializeRequiredBridgeFields() {
    val track = PulseTrack(id = "t1", title = "Song", artist = "Artist", durationSec = 180)
    val snapshot =
      PlaybackSnapshot(
        queue = listOf(track),
        currentIndex = 0,
        isPlaying = true,
        isBuffering = false,
        playbackEnded = false,
        positionSec = 42.5,
        durationSec = 180.0,
        bufferedPercent = 80,
        shuffleEnabled = true,
        repeatEnabled = false,
        radioMode = false,
        autoplayEnabled = true,
        volumePercent = 85,
        sleepRemainingMs = 300_000L,
        sleepEndOfSong = false,
        audioQuality = "high",
        errorMessage = "",
      )
    val json = snapshot.toJson()
    assertEquals("native-media3", json.getString("engine"))
    assertEquals(0, json.getInt("index"))
    assertEquals(true, json.getBoolean("isPlaying"))
    assertEquals(85, json.getInt("volume"))
    assertEquals("high", json.getString("audioQuality"))

    val progress =
      DownloadProgressItem(
        track = track,
        status = DownloadStatus.DOWNLOADING,
        progressPercent = 67,
        bytesDownloaded = 670_000L,
        totalBytes = 1_000_000L,
      ).toJson()
    assertEquals("downloading", progress.getString("status"))
    assertEquals(67, progress.getInt("progress"))

    val saved =
      DownloadedSongRecord(
        track = track,
        localUri = "content://media/external/audio/media/42",
        fileName = "Artist - Song.m4a",
        mimeType = "audio/mp4",
        sizeBytes = 3_200_000L,
        addedAtMs = 1_700_000_000_000L,
      )
    val savedRebuilt = DownloadedSongRecord.fromJson(saved.toJson())
    assertEquals(saved.localUri, savedRebuilt?.localUri)
    assertEquals("Artist - Song.m4a", savedRebuilt?.fileName)
  }

  @Test
  fun bridgeOriginCheck_acceptsOnlyHttpsAppAssetsOrigin() {
    assertTrue(PulseJsBridge.isTrustedAppUrl("https://appassets.androidplatform.net/assets/index.html"))
    assertTrue(PulseJsBridge.isTrustedAppUrl("https://appassets.androidplatform.net"))
    assertFalse(PulseJsBridge.isTrustedAppUrl("http://appassets.androidplatform.net/assets/index.html"))
    assertFalse(PulseJsBridge.isTrustedAppUrl("https://appassets.androidplatform.net.attacker.com/index.html"))
    assertFalse(PulseJsBridge.isTrustedAppUrl("file:///android_asset/index.html"))
    assertFalse(PulseJsBridge.isTrustedAppUrl("javascript:alert(1)"))
    assertFalse(PulseJsBridge.isTrustedAppUrl(null))
    assertFalse(PulseJsBridge.isTrustedAppUrl(" "))
  }

  @Test
  fun bridgeParseStringList_filtersEmptyAndMalformedInput() {
    assertEquals(
      listOf("id1", "id2"),
      PulseJsBridge.parseStringList("""["id1", " ", "", "id2"]"""),
    )
    assertTrue(PulseJsBridge.parseStringList("bad json").isEmpty())
    assertTrue(PulseJsBridge.parseStringList(null).isEmpty())
  }
}
