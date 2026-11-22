package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

  private var webView: WebView? = null
  private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
  private var pendingExportBytes: ByteArray? = null
  private var pendingDownloadTrackJson: String? = null

  private val pickImageLauncher =
    registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      completeFileChooser(uri?.let { arrayOf(it) })
    }

  private val openDocumentLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      completeFileChooser(uri?.let { arrayOf(it) })
    }

  private val createDocumentLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val bytes = pendingExportBytes
      val dlTrackJson = pendingDownloadTrackJson
      pendingExportBytes = null
      pendingDownloadTrackJson = null
      val uri = result.data?.data
      if (result.resultCode == RESULT_OK && uri != null && bytes != null) {
        lifecycleScope.launch(Dispatchers.IO) {
          val ok =
            try {
              contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
              true
            } catch (_: Exception) {
              false
            }
          withContext(Dispatchers.Main) {
            if (!ok) {
              Toast.makeText(this@MainActivity, "Could not save file", Toast.LENGTH_SHORT).show()
            } else if (dlTrackJson != null) {
              notifySongDownloaded(dlTrackJson, uri.toString())
            }
          }
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )

    val assetLoader =
      WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
        .build()

    setContent {
      var canGoBack by remember { mutableStateOf(false) }

      BackHandler(enabled = canGoBack) {
        val current = webView
        if (current != null && current.canGoBack()) {
          current.goBack()
        } else {
          canGoBack = false
        }
      }

      Scaffold(
        modifier = Modifier.fillMaxSize().testTag("spotify_webview_container"),
        containerColor = Color(0xFF0A0E1E),
        contentWindowInsets = WindowInsets.safeDrawing.union(WindowInsets.ime),
      ) { innerPadding ->
        AndroidView(
          modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("spotify_webview"),
          factory = { context ->
            createConfiguredWebView(
              context = context,
              assetLoader = assetLoader,
              onCanGoBackChanged = { canGoBack = it },
            ).also { created ->
              webView = created
              created.loadUrl(APP_ENTRY_URL)
            }
          },
        )
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createConfiguredWebView(
    context: android.content.Context,
    assetLoader: WebViewAssetLoader,
    onCanGoBackChanged: (Boolean) -> Unit,
  ): WebView {
    return WebView(context).apply {
      layoutParams =
        ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
      setBackgroundColor(android.graphics.Color.parseColor("#0A0E1E"))
      overScrollMode = WebView.OVER_SCROLL_NEVER

      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadsImagesAutomatically = true
        allowFileAccess = false
        allowContentAccess = true
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        useWideViewPort = true
        loadWithOverviewMode = true
      }

      addJavascriptInterface(ExportBridge(), "AndroidBridge")

      webViewClient =
        object : WebViewClient() {
          override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
          ): WebResourceResponse? {
            val url = request?.url ?: return null
            return assetLoader.shouldInterceptRequest(url)
          }

          override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
          ): Boolean {
            val uri = request?.url ?: return false
            if (uri.host == APP_ASSET_HOST) return false
            if (!request.isForMainFrame) return false
            openExternalUri(uri)
            return true
          }

          override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            onCanGoBackChanged(view?.canGoBack() == true)
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onCanGoBackChanged(view?.canGoBack() == true)
            view?.evaluateJavascript(BLOB_EXPORT_HOOK_JS, null)
          }
        }

      webChromeClient =
        object : WebChromeClient() {
          override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
          ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            val acceptTypes = fileChooserParams?.acceptTypes.orEmpty()
            val wantsImage = acceptTypes.any { it.contains("image", ignoreCase = true) }
            return try {
              if (wantsImage) {
                pickImageLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
              } else {
                openDocumentLauncher.launch(arrayOf("*/*"))
              }
              true
            } catch (_: Exception) {
              completeFileChooser(null)
              false
            }
          }

          override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?,
          ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val tempWebView = WebView(context)
            tempWebView.webViewClient =
              object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                  v: WebView?,
                  request: WebResourceRequest?,
                ): Boolean {
                  request?.url?.let { openExternalUri(it) }
                  tempWebView.destroy()
                  return true
                }
              }
            transport.webView = tempWebView
            resultMsg.sendToTarget()
            return true
          }
        }
    }
  }

  private fun completeFileChooser(uris: Array<Uri>?) {
    pendingFileCallback?.onReceiveValue(uris)
    pendingFileCallback = null
  }

  private fun openExternalUri(uri: Uri) {
    try {
      startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
      Toast.makeText(this, "No app found to open link", Toast.LENGTH_SHORT).show()
    }
  }

  private inner class ExportBridge {
    @JavascriptInterface
    fun saveExportFile(filename: String, mimeType: String, base64Content: String) {
      val bytes =
        try {
          Base64.decode(base64Content, Base64.DEFAULT)
        } catch (_: Exception) {
          return
        }
      runOnUiThread {
        pendingExportBytes = bytes
        val intent =
          Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, filename.ifBlank { "export" })
          }
        try {
          createDocumentLauncher.launch(intent)
        } catch (_: Exception) {
          pendingExportBytes = null
          Toast.makeText(this@MainActivity, "Could not open file saver", Toast.LENGTH_SHORT).show()
        }
      }
    }

    @JavascriptInterface
    fun downloadSong(
      filename: String,
      title: String,
      artist: String,
      urlsJson: String,
      trackJson: String,
    ) {
      val urls =
        try {
          val arr = JSONArray(urlsJson)
          List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
          emptyList()
        }
      if (urls.isEmpty()) {
        showWebToast("Download unavailable for this song")
        return
      }
      lifecycleScope.launch(Dispatchers.IO) {
        performSongDownload(
          baseFilename = filename.ifBlank { "song.m4a" },
          title = title.ifBlank { "Song" },
          artist = artist,
          urls = urls,
          trackJson = trackJson,
        )
      }
    }
  }

  private suspend fun performSongDownload(
    baseFilename: String,
    title: String,
    artist: String,
    urls: List<String>,
    trackJson: String,
  ) {
    withContext(Dispatchers.Main) { showWebToast("Downloading $title…") }
    for (rawUrl in urls) {
      var conn: HttpURLConnection? = null
      try {
        conn = openFollowedConnection(rawUrl) ?: continue
        val contentType = (conn.contentType ?: "").lowercase()
        if (contentType.contains("text/html") || contentType.contains("application/json")) {
          continue
        }
        val (ext, mime) = resolveAudioFormat(contentType, baseFilename)
        val stem = baseFilename.replace(Regex("\\.[a-zA-Z0-9]+$"), "")
        val finalName = "$stem.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val values =
            ContentValues().apply {
              put(MediaStore.Audio.Media.DISPLAY_NAME, finalName)
              put(MediaStore.Audio.Media.MIME_TYPE, mime)
              put(MediaStore.Audio.Media.TITLE, title)
              if (artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
              put(MediaStore.Audio.Media.IS_MUSIC, 1)
              put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Pulse")
              put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
          val resolver = contentResolver
          val itemUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: continue
          var totalBytes = 0L
          try {
            resolver.openOutputStream(itemUri)?.use { out ->
              conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                  val read = input.read(buf)
                  if (read <= 0) break
                  out.write(buf, 0, read)
                  totalBytes += read
                }
              }
            }
            if (totalBytes < 2048L) {
              resolver.delete(itemUri, null, null)
              continue
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            withContext(Dispatchers.Main) { notifySongDownloaded(trackJson, itemUri.toString()) }
            return
          } catch (_: Exception) {
            resolver.delete(itemUri, null, null)
          }
        } else {
          val buffer = ByteArrayOutputStream()
          conn.inputStream.use { input ->
            val chunk = ByteArray(16 * 1024)
            while (true) {
              val read = input.read(chunk)
              if (read <= 0) break
              buffer.write(chunk, 0, read)
            }
          }
          val bytes = buffer.toByteArray()
          if (bytes.size < 2048) continue
          withContext(Dispatchers.Main) {
            pendingExportBytes = bytes
            pendingDownloadTrackJson = trackJson
            val intent =
              Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, finalName)
              }
            try {
              createDocumentLauncher.launch(intent)
            } catch (_: Exception) {
              pendingExportBytes = null
              pendingDownloadTrackJson = null
              showWebToast("Could not open file saver")
            }
          }
          return
        }
      } catch (_: Exception) {
        // Try next candidate stream URL.
      } finally {
        conn?.disconnect()
      }
    }
    withContext(Dispatchers.Main) { showWebToast("Could not download this song right now") }
  }

  private fun openFollowedConnection(initialUrl: String): HttpURLConnection? {
    var currentUrl = initialUrl
    repeat(6) {
      val conn =
        (URL(currentUrl).openConnection() as? HttpURLConnection)?.apply {
          instanceFollowRedirects = false
          connectTimeout = 15000
          readTimeout = 30000
          setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
          )
        } ?: return null
      val code = conn.responseCode
      if (code in 300..399) {
        val loc = conn.getHeaderField("Location")
        conn.disconnect()
        if (loc.isNullOrBlank()) return null
        currentUrl = URL(URL(currentUrl), loc).toString()
      } else if (code in 200..299) {
        return conn
      } else {
        conn.disconnect()
        return null
      }
    }
    return null
  }

  private fun resolveAudioFormat(contentType: String, baseFilename: String): Pair<String, String> {
    return when {
      contentType.contains("webm") -> "webm" to "audio/webm"
      contentType.contains("ogg") || contentType.contains("opus") -> "ogg" to "audio/ogg"
      contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3" to "audio/mpeg"
      contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac") ->
        "m4a" to "audio/mp4"
      baseFilename.endsWith(".webm", ignoreCase = true) -> "webm" to "audio/webm"
      baseFilename.endsWith(".ogg", ignoreCase = true) -> "ogg" to "audio/ogg"
      baseFilename.endsWith(".mp3", ignoreCase = true) -> "mp3" to "audio/mpeg"
      else -> "m4a" to "audio/mp4"
    }
  }

  private fun notifySongDownloaded(trackJson: String, localUri: String) {
    val wv = webView
    if (wv != null) {
      wv.post {
        wv.evaluateJavascript(
          "window.onSongDownloaded && window.onSongDownloaded(${JSONObject.quote(trackJson)}, ${JSONObject.quote(localUri)})",
          null,
        )
      }
    } else {
      runOnUiThread { Toast.makeText(this, "Saved to Music/Pulse", Toast.LENGTH_SHORT).show() }
    }
  }

  private fun showWebToast(message: String) {
    val wv = webView
    if (wv != null) {
      wv.post {
        wv.evaluateJavascript("window.toast && toast(${JSONObject.quote(message)})", null)
      }
    } else {
      runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
  }

  override fun onDestroy() {
    webView?.let { wv ->
      (wv.parent as? ViewGroup)?.removeView(wv)
      wv.destroy()
    }
    webView = null
    super.onDestroy()
  }

  companion object {
    private const val APP_ASSET_HOST = "appassets.androidplatform.net"
    private const val APP_ENTRY_URL = "https://$APP_ASSET_HOST/assets/index.html"

    private const val BLOB_EXPORT_HOOK_JS =
      """
      (function() {
        if (window.__androidDownloadHooked || !window.AndroidBridge) return;
        window.__androidDownloadHooked = true;
        const blobMap = new Map();
        const origCreate = URL.createObjectURL.bind(URL);
        const origRevoke = URL.revokeObjectURL.bind(URL);
        URL.createObjectURL = function(obj) {
          const url = origCreate(obj);
          if (obj instanceof Blob) blobMap.set(url, obj);
          return url;
        };
        URL.revokeObjectURL = function(url) {
          blobMap.delete(url);
          return origRevoke(url);
        };
        const origClick = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
          if (this.download && this.href && this.href.startsWith('blob:')) {
            const blob = blobMap.get(this.href);
            if (blob) {
              const fname = this.download;
              const mime = (blob.type || 'application/octet-stream').split(';')[0];
              const reader = new FileReader();
              reader.onloadend = function() {
                const res = String(reader.result || '');
                const idx = res.indexOf(',');
                if (idx >= 0) {
                  window.AndroidBridge.saveExportFile(fname, mime, res.slice(idx + 1));
                }
              };
              reader.readAsDataURL(blob);
              return;
            }
          }
          return origClick.apply(this, arguments);
        };
      })();
      """
  }
}
