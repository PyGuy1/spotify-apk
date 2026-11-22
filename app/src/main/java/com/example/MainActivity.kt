package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.view.ViewGroup
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.example.bridge.PulseJsBridge
import com.example.playback.PulsePlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

  private var webView: WebView? = null
  private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
  private var pendingExportBytes: ByteArray? = null
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private lateinit var pulseBridge: PulseJsBridge

  private val requestNotificationsPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

  private val pickImageLauncher =
    registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      completeFileChooser(uri?.let { arrayOf(it) })
    }

  private val openDocumentLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      completeFileChooser(uri?.let { arrayOf(it) })
    }

  private val importAudioFilesLauncher =
    registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNullOrEmpty()) return@registerForActivityResult
      lifecycleScope.launch(Dispatchers.IO) {
        val runtime = PulseRuntime.get(this@MainActivity)
        var added = 0
        for (uri in uris) {
          try {
            contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          } catch (_: Exception) {}
          if (runtime.downloadedRepo.importExternalUri(uri) != null) {
            added++
          }
        }
        runtime.downloadManager.syncLibraryAndNotify()
        withContext(Dispatchers.Main) {
          if (added > 0) {
            Toast.makeText(
              this@MainActivity,
              "Added $added audio file${if (added == 1) "" else "s"} to Downloaded Songs",
              Toast.LENGTH_SHORT,
            ).show()
          }
        }
      }
    }

  private val createDocumentLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val bytes = pendingExportBytes
      pendingExportBytes = null
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
          if (!ok) {
            withContext(Dispatchers.Main) {
              Toast.makeText(this@MainActivity, "Could not save file", Toast.LENGTH_SHORT).show()
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

    requestNotificationPermissionIfNeeded()

    pulseBridge =
      PulseJsBridge(
        context = applicationContext,
        webViewProvider = { webView },
        ensureServiceStarted = { ensureMediaControllerConnected() },
        onSaveExportFile = { filename, mimeType, base64 ->
          launchCreateDocumentExport(filename, mimeType, base64)
        },
        onRequestImportLocalAudio = {
          try {
            importAudioFilesLauncher.launch(arrayOf("audio/*"))
          } catch (_: Exception) {
            Toast.makeText(this, "Could not open audio picker", Toast.LENGTH_SHORT).show()
          }
        },
      )
    pulseBridge.attach()

    lifecycleScope.launch(Dispatchers.IO) {
      PulseRuntime.get(this@MainActivity).downloadManager.syncLibraryAndNotify()
    }

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

  override fun onStart() {
    super.onStart()
    ensureMediaControllerConnected()
    lifecycleScope.launch(Dispatchers.IO) {
      PulseRuntime.get(this@MainActivity).downloadManager.syncLibraryAndNotify()
    }
  }

  override fun onStop() {
    val future = controllerFuture
    controllerFuture = null
    if (future != null) {
      MediaController.releaseFuture(future)
    }
    super.onStop()
  }

  private fun ensureMediaControllerConnected() {
    if (controllerFuture != null) return
    val token = SessionToken(this, ComponentName(this, PulsePlaybackService::class.java))
    val future = MediaController.Builder(this, token).buildAsync()
    controllerFuture = future
    future.addListener(
      {
        try {
          future.get()
        } catch (_: Exception) {}
      },
      MoreExecutors.directExecutor(),
    )
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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

      addJavascriptInterface(pulseBridge, "AndroidBridge")

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
            pulseBridge.lastKnownMainFrameUrl = url.orEmpty()
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

  private fun launchCreateDocumentExport(filename: String, mimeType: String, base64: String) {
    val bytes =
      try {
        Base64.decode(base64, Base64.DEFAULT)
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
        Toast.makeText(this, "Could not open file saver", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun openExternalUri(uri: Uri) {
    try {
      startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
      Toast.makeText(this, "No app found to open link", Toast.LENGTH_SHORT).show()
    }
  }

  override fun onDestroy() {
    pulseBridge.detach()
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
