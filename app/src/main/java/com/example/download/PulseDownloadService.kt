package com.example.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.example.MainActivity
import com.example.PulseRuntime
import com.example.R
import com.example.playback.PulseMediaDataSource

@OptIn(UnstableApi::class)
class PulseDownloadService : DownloadService(
  FOREGROUND_NOTIFICATION_ID,
  DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
  DOWNLOAD_CHANNEL_ID,
  R.string.download_channel_name,
  R.string.download_channel_description,
) {
  private var notificationHelper: DownloadNotificationHelper? = null

  override fun getDownloadManager(): DownloadManager {
    val runtime = PulseRuntime.get(this)
    val upstreamFactory =
      PulseMediaDataSource.Factory(
        context = this,
        streamResolver = runtime.streamResolver,
        downloadedRepo = runtime.downloadedRepo,
        qualityProvider = { null },
        okHttpClient = runtime.okHttpClient,
      )
    return runtime.cacheModule.getOrCreateMedia3DownloadManager(upstreamFactory)
  }

  override fun getScheduler(): Scheduler? = null

  override fun getForegroundNotification(
    downloads: MutableList<Download>,
    notMetRequirements: Int,
  ): Notification {
    val helper =
      notificationHelper
        ?: DownloadNotificationHelper(this, DOWNLOAD_CHANNEL_ID).also { notificationHelper = it }
    val launchIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val contentIntent =
      PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    return helper.buildProgressNotification(
      this,
      R.drawable.ic_pulse_notification,
      contentIntent,
      "Downloading music for offline playback",
      downloads,
      notMetRequirements,
    )
  }

  companion object {
    const val FOREGROUND_NOTIFICATION_ID = 41_001
    const val DOWNLOAD_CHANNEL_ID = "pulse_downloads"
  }
}
