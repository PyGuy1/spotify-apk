package com.example

import com.example.download.SongFilenameUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongFilenameUtilsTest {

  @Test
  fun sanitizeBaseName_stripsIllegalCharactersAndCombinesArtistTitle() {
    val sanitized = SongFilenameUtils.sanitizeBaseName("Starboy: Live/Remix?", "The Weeknd | *Feat*")
    assertEquals("The Weeknd Feat - Starboy Live Remix", sanitized)
  }

  @Test
  fun sanitizeBaseName_handlesBlankValuesAndLengthLimit() {
    assertEquals("Pulse Track", SongFilenameUtils.sanitizeBaseName("   ", "..."))
    val longName = SongFilenameUtils.sanitizeBaseName("A".repeat(150), "B".repeat(50), maxLength = 64)
    assertTrue(longName.length <= 64)
  }

  @Test
  fun deduplicateFileName_incrementsSafelyCaseInsensitively() {
    val existing = setOf("Daft Punk - Instant Crush.m4a", "daft punk - instant crush (2).m4a")
    val unique = SongFilenameUtils.deduplicateFileName("Daft Punk - Instant Crush", "m4a", existing)
    assertEquals("Daft Punk - Instant Crush (3).m4a", unique)
  }

  @Test
  fun inspectStreamFormat_rejectsHtmlJsonXmlAndEmptyPayloads() {
    val htmlBytes = "<!DOCTYPE html><html><body>Error 403</body></html>".toByteArray()
    val htmlRes = SongFilenameUtils.inspectStreamFormat("text/html; charset=utf-8", htmlBytes)
    assertFalse(htmlRes.isValidAudio)

    val jsonRes = SongFilenameUtils.inspectStreamFormat("application/octet-stream", "{\"error\":\"rate limit\"}".toByteArray())
    assertFalse(jsonRes.isValidAudio)

    val m3uRes = SongFilenameUtils.inspectStreamFormat("application/octet-stream", "#EXTM3U\n#EXT-X-VERSION:3".toByteArray())
    assertFalse(m3uRes.isValidAudio)

    val emptyRes = SongFilenameUtils.inspectStreamFormat("audio/mp4", ByteArray(0))
    assertFalse(emptyRes.isValidAudio)
  }

  @Test
  fun inspectStreamFormat_detectsValidAudioContainersByMagicBytes() {
    val m4aHeader = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte(), 0, 0, 0, 0)
    val m4aInfo = SongFilenameUtils.inspectStreamFormat("application/octet-stream", m4aHeader)
    assertTrue(m4aInfo.isValidAudio)
    assertEquals("m4a", m4aInfo.extension)
    assertEquals("audio/mp4", m4aInfo.mimeType)

    val webmHeader = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte(), 1, 0, 0, 0, 0, 0, 0, 0)
    val webmInfo = SongFilenameUtils.inspectStreamFormat("application/octet-stream", webmHeader)
    assertTrue(webmInfo.isValidAudio)
    assertEquals("webm", webmInfo.extension)

    val flacHeader = "fLaC\u0000\u0000\u0000\u0022paddingbytes1234".toByteArray()
    val flacInfo = SongFilenameUtils.inspectStreamFormat("application/octet-stream", flacHeader)
    assertTrue(flacInfo.isValidAudio)
    assertEquals("flac", flacInfo.extension)

    val mp3Header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val mp3Info = SongFilenameUtils.inspectStreamFormat("audio/mpeg", mp3Header)
    assertTrue(mp3Info.isValidAudio)
    assertEquals("mp3", mp3Info.extension)
  }
}
