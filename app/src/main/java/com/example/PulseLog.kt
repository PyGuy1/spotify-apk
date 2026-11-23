package com.example

import android.util.Log

internal object PulseLog {
  fun d(tag: String, msg: String) {
    try {
      Log.d(tag, msg)
    } catch (_: Throwable) {
      // Pure JVM unit test environment without Android framework stubs
    }
  }

  fun w(tag: String, msg: String, tr: Throwable? = null) {
    try {
      if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    } catch (_: Throwable) {}
  }

  fun e(tag: String, msg: String, tr: Throwable? = null) {
    try {
      if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    } catch (_: Throwable) {}
  }

  fun redactUrl(rawUrl: String): String {
    if (rawUrl.isBlank()) return "<empty>"
    return try {
      val uri = java.net.URI(rawUrl)
      val host = uri.host ?: "<unknown-host>"
      val scheme = uri.scheme ?: "https"
      val path = uri.rawPath.orEmpty().take(48)
      "$scheme://$host$path"
    } catch (_: Exception) {
      "<malformed-url>"
    }
  }
}
