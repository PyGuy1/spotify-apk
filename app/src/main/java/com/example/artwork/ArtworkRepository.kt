package com.example.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import com.example.model.PulseTrack
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

class ArtworkRepository(
  private val context: Context,
  private val httpClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(6, TimeUnit.SECONDS)
      .readTimeout(10, TimeUnit.SECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build(),
) {
  private val cacheDir = File(context.cacheDir, "pulse_artwork").apply { mkdirs() }
  private val memoryCache =
    object : LruCache<String, ByteArray>(6 * 1024 * 1024) {
      override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
  private val inflightMutex = Mutex()
  private val inflight = mutableMapOf<String, CompletableDeferred<ByteArray>>()
  private val itunesArtCache = mutableMapOf<String, String>()

  private val fallbackArtBytes: ByteArray by lazy { renderPulseFallbackArtBytes(512) }

  fun getFallbackBytes(): ByteArray = fallbackArtBytes

  fun getCachedArtworkBytes(trackId: String): ByteArray? {
    if (trackId.isBlank()) return null
    memoryCache.get(trackId)?.let { return it }
    val diskFile = diskFileFor(trackId)
    if (diskFile.exists() && diskFile.length() in 128..MAX_DISK_ART_BYTES) {
      return try {
        val bytes = diskFile.readBytes()
        if (bytes.isNotEmpty()) memoryCache.put(trackId, bytes)
        bytes
      } catch (_: Exception) {
        null
      }
    }
    return null
  }

  suspend fun getOrFetchArtworkBytes(track: PulseTrack): ByteArray {
    getCachedArtworkBytes(track.id)?.let { return it }

    val deferred: CompletableDeferred<ByteArray>
    val owner: Boolean
    inflightMutex.withLock {
      val existing = inflight[track.id]
      if (existing != null && !existing.isCompleted) {
        deferred = existing
        owner = false
      } else {
        val created = CompletableDeferred<ByteArray>()
        inflight[track.id] = created
        deferred = created
        owner = true
      }
    }

    if (!owner) return deferred.await()

    return try {
      val resolved =
        withContext(Dispatchers.IO) { resolveArtworkCascade(track) } ?: fallbackArtBytes
      if (resolved !== fallbackArtBytes) {
        saveToCache(track.id, resolved)
      }
      deferred.complete(resolved)
      resolved
    } catch (c: CancellationException) {
      deferred.cancel(c)
      throw c
    } catch (_: Exception) {
      deferred.complete(fallbackArtBytes)
      fallbackArtBytes
    } finally {
      inflightMutex.withLock {
        if (inflight[track.id] === deferred) inflight.remove(track.id)
      }
    }
  }

  private fun resolveArtworkCascade(track: PulseTrack): ByteArray? {
    // 1. Embedded artwork from local file if available
    if (track.localUri.isNotBlank()) {
      extractEmbeddedCoverBytes(track.localUri)?.let { raw ->
        decodeCropSquareJpeg(raw)?.let { return it }
      }
    }

    val candidates = mutableListOf<String>()

    // 2. Track's explicit thumbnail URL (upgraded if Google/YouTube Music square art)
    val explicit = normalizeArtUrl(track.thumbUrl, 600)
    if (explicit.isNotBlank()) candidates.add(explicit)

    // 3. iTunes square HD lookup when track isn't already a lh3 square cover
    if (!track.thumbUrl.contains("googleusercontent") && !track.thumbUrl.contains("ggpht")) {
      lookupItunesSquareArt(track)?.let { candidates.add(it) }
    }

    // 4. YouTube high-res to standard fallback chain
    if (track.id.isNotBlank() && !track.id.startsWith("local_")) {
      candidates.add("https://i.ytimg.com/vi/${track.id}/hq720.jpg")
      candidates.add("https://i.ytimg.com/vi/${track.id}/maxresdefault.jpg")
      candidates.add("https://i.ytimg.com/vi/${track.id}/mqdefault.jpg")
      candidates.add("https://i.ytimg.com/vi/${track.id}/hqdefault.jpg")
    }

    for (url in candidates.distinct()) {
      val downloaded = downloadBytes(url) ?: continue
      val squareJpeg = decodeCropSquareJpeg(downloaded)
      if (squareJpeg != null) return squareJpeg
    }
    return null
  }

  private fun extractEmbeddedCoverBytes(uriString: String): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
      val uri = Uri.parse(uriString)
      if (uri.scheme == "file" && uri.path != null) {
        retriever.setDataSource(uri.path)
      } else {
        retriever.setDataSource(context, uri)
      }
      retriever.embeddedPicture
    } catch (_: Exception) {
      null
    } finally {
      try {
        retriever.release()
      } catch (_: Exception) {}
    }
  }

  private fun lookupItunesSquareArt(track: PulseTrack): String? {
    itunesArtCache[track.id]?.let { return it }
    val title = cleanTitle(track.title)
    if (title.isBlank()) return null
    val artist = cleanArtist(track.artist)
    val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "https://itunes.apple.com/search?media=music&entity=song&limit=6&term=$encoded"
    return try {
      val req = Request.Builder().url(url).build()
      val body =
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return null
          resp.body?.string().orEmpty()
        }
      if (body.isBlank()) return null
      val results = JSONObject(body).optJSONArray("results") ?: return null
      var bestUrl: String? = null
      var bestScore = Double.NEGATIVE_INFINITY
      val artistTokens =
        artist.lowercase().split(Regex("[\\s,&]+")).filter { it.length > 2 }
      for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val rTitle = item.optString("trackName", "")
        val rArtist = item.optString("artistName", "").lowercase()
        val art100 = item.optString("artworkUrl100", "")
        if (art100.isBlank()) continue
        val sim = jaccard(rTitle, title)
        val artistMatch =
          artistTokens.isEmpty() || artistTokens.any { rArtist.contains(it) }
        val durationSec = track.durationSec
        val rDurMs = item.optLong("trackTimeMillis", 0L)
        val durDiff =
          if (durationSec != null && durationSec > 0 && rDurMs > 0) {
            abs(durationSec - rDurMs / 1000.0)
          } else {
            0.0
          }
        if (sim >= 0.65 && artistMatch && durDiff <= 22.0) {
          val score = sim - durDiff / 100.0
          if (score > bestScore) {
            bestScore = score
            bestUrl = art100.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.")
          }
        }
      }
      if (bestUrl != null) {
        itunesArtCache[track.id] = bestUrl
      }
      bestUrl
    } catch (_: Exception) {
      null
    }
  }

  private fun downloadBytes(url: String): ByteArray? {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    return try {
      val req =
        Request.Builder()
          .url(url)
          .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
          )
          .build()
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val contentType = resp.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("text/html") || contentType.contains("application/json")) {
          return null
        }
        val bytes = resp.body?.bytes() ?: return null
        if (bytes.size < 256 || bytes.size > 8 * 1024 * 1024) return null
        bytes
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun saveToCache(trackId: String, bytes: ByteArray) {
    memoryCache.put(trackId, bytes)
    try {
      diskFileFor(trackId).writeBytes(bytes)
    } catch (_: Exception) {}
  }

  private fun diskFileFor(trackId: String): File {
    val safeName = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(cacheDir, "$safeName.jpg")
  }

  @UnstableApi
  fun createMedia3BitmapLoader(): BitmapLoader {
    val delegate = CacheBitmapLoader(DataSourceBitmapLoader(context))
    return object : BitmapLoader {
      override fun supportsMimeType(mimeType: String): Boolean =
        delegate.supportsMimeType(mimeType)

      override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val decoded =
          BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: BitmapFactory.decodeByteArray(fallbackArtBytes, 0, fallbackArtBytes.size)
        return if (decoded != null) {
          Futures.immediateFuture(decoded)
        } else {
          delegate.decodeBitmap(data)
        }
      }

      override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        return delegate.loadBitmap(uri)
      }
    }
  }

  companion object {
    private const val MAX_DISK_ART_BYTES = 1024 * 1024L
    private const val TARGET_SQUARE_PX = 512

    fun normalizeArtUrl(rawUrl: String, px: Int = 600): String {
      if (rawUrl.isBlank()) return ""
      return if (rawUrl.contains("googleusercontent") || rawUrl.contains("ggpht")) {
        rawUrl.replace(Regex("=(?:w\\d+-h\\d+|s\\d+)[^=?#]*$"), "=w$px-h$px-l90-rj")
      } else {
        rawUrl
      }
    }

    fun decodeCropSquareJpeg(rawBytes: ByteArray, targetSize: Int = TARGET_SQUARE_PX): ByteArray? {
      if (rawBytes.isEmpty()) return null
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
      val w = bounds.outWidth
      val h = bounds.outHeight
      if (w <= 0 || h <= 0) return null

      var sampleSize = 1
      val minSide = min(w, h)
      while (minSide / (sampleSize * 2) >= targetSize) {
        sampleSize *= 2
      }

      val opts =
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
      return try {
        val side = min(decoded.width, decoded.height)
        val x = (decoded.width - side) / 2
        val y = (decoded.height - side) / 2
        val cropped = Bitmap.createBitmap(decoded, x, y, side, side)
        val finalBmp =
          if (side != targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
          } else {
            cropped
          }
        val out = ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (finalBmp !== cropped && finalBmp !== decoded) finalBmp.recycle()
        if (cropped !== decoded) cropped.recycle()
        decoded.recycle()
        out.toByteArray()
      } catch (_: Exception) {
        try {
          decoded.recycle()
        } catch (_: Exception) {}
        null
      }
    }

    fun renderPulseFallbackArtBytes(sizePx: Int = 512): ByteArray {
      val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bmp)
      val bgPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader =
            LinearGradient(
              0f,
              0f,
              sizePx.toFloat(),
              sizePx.toFloat(),
              intArrayOf(Color.rgb(24, 42, 94), Color.rgb(12, 18, 42)),
              null,
              Shader.TileMode.CLAMP,
            )
        }
      canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgPaint)

      val glowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.argb(65, 122, 162, 255)
          style = Paint.Style.STROKE
          strokeWidth = sizePx * 0.045f
        }
      val pad = sizePx * 0.18f
      canvas.drawOval(RectF(pad, pad, sizePx - pad, sizePx - pad), glowPaint)

      val notePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.rgb(176, 194, 255)
          style = Paint.Style.FILL
        }
      val cx = sizePx * 0.43f
      val cy = sizePx * 0.64f
      val r = sizePx * 0.09f
      canvas.drawCircle(cx, cy, r, notePaint)
      canvas.drawRect(cx + r * 0.65f, sizePx * 0.28f, cx + r * 1.1f, cy, notePaint)
      canvas.drawRect(
        cx + r * 0.65f,
        sizePx * 0.28f,
        sizePx * 0.68f,
        sizePx * 0.35f,
        notePaint,
      )

      val out = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
      bmp.recycle()
      return out.toByteArray()
    }

    private fun cleanTitle(raw: String): String =
      raw
        .replace(Regex("\\(.*?\\)|\\[.*?]|【.*?】|「.*?」"), " ")
        .replace(
          Regex(
            "\\s*[-–—|:]\\s*(from|official|lyric|audio|video|remaster|live|sped|slowed|version|ost|theme).*$",
            RegexOption.IGNORE_CASE,
          ),
          "",
        )
        .replace(Regex("\\b(ft|feat)\\.?\\s.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    private fun cleanArtist(raw: String): String =
      raw
        .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*(official|music)$", RegexOption.IGNORE_CASE), "")
        .split(Regex("\\s*(?:,|&|\\bx\\b|\\bfeat\\.?|\\bft\\.?)\\s*", RegexOption.IGNORE_CASE))
        .firstOrNull()
        .orEmpty()
        .trim()

    private fun jaccard(a: String, b: String): Double {
      val wa = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
      val wb = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
      if (wa.isEmpty() || wb.isEmpty()) return 0.0
      val inter = wa.count { wb.contains(it) }
      return inter.toDouble() / (wa.size + wb.size - inter)
    }
  }
}
