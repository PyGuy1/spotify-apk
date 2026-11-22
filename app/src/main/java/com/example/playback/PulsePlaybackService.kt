package com.example.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.PulseRuntime
import com.example.R
import com.example.model.PlaybackSnapshot
import com.example.model.PulseTrack
import com.example.stream.StreamResolver
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

@OptIn(UnstableApi::class)
class PulsePlaybackService : MediaSessionService() {

  interface Listener {
    fun onPlaybackSnapshot(snapshot: PlaybackSnapshot)
  }

  private lateinit var runtime: PulseRuntime
  private lateinit var player: ExoPlayer
  private var mediaSession: MediaSession? = null
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val queueTracks = mutableListOf<PulseTrack>()
  private var shuffleBackupUpcoming: List<PulseTrack> = emptyList()
  private var shuffleEnabled = false
  private var repeatEnabled = false
  private var radioMode = false
  private var autoplayEnabled = true
  private var volumePercent = 80
  private var audioQuality = "normal"
  private var lastErrorMessage = ""
  private var recoveryAttemptsForTrack = 0
  private var lastRecoveryTrackId = ""
  private var playbackEndedFlag = false

  private var sleepDeadlineMs = 0L
  private var sleepEndOfSong = false
  private var sleepJob: Job? = null
  private var tickerJob: Job? = null
  private var autoplayJob: Job? = null
  private var lastPositionPersistMs = 0L

  override fun onCreate() {
    super.onCreate()
    runtime = PulseRuntime.get(this)
    val saved = runtime.stateStore.load()
    shuffleEnabled = saved.shuffleEnabled
    repeatEnabled = saved.repeatEnabled
    radioMode = saved.radioMode
    autoplayEnabled = saved.autoplayEnabled
    volumePercent = saved.volumePercent
    audioQuality = saved.audioQuality
    shuffleBackupUpcoming = saved.shuffleBackupUpcoming

    val loadControl =
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
        .setBackBuffer(5_000, false)
        .build()

    val mediaSourceFactory =
      DefaultMediaSourceFactory(
        PulseMediaDataSource.Factory(
          context = this,
          streamResolver = runtime.streamResolver,
          downloadedRepo = runtime.downloadedRepo,
          qualityProvider = { audioQuality },
          okHttpClient = runtime.okHttpClient,
        )
      )

    player =
      ExoPlayer.Builder(this)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
          /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLooper(Looper.getMainLooper())
        .build()

    player.volume = (volumePercent / 100f).coerceIn(0f, 1f)
    player.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    player.addListener(playerListener)

    val launchIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val sessionActivity =
      PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    mediaSession =
      MediaSession.Builder(this, player)
        .setSessionActivity(sessionActivity)
        .setBitmapLoader(runtime.artworkRepository.createMedia3BitmapLoader())
        .setCallback(SessionCallback())
        .build()

    val notificationProvider =
      DefaultMediaNotificationProvider.Builder(this).build().apply {
        setSmallIcon(R.drawable.ic_pulse_notification)
      }
    setMediaNotificationProvider(notificationProvider)

    if (saved.queue.isNotEmpty()) {
      restoreSavedQueue(saved.queue, saved.currentIndex, saved.positionMs, autoPlay = false)
    }

    activeService = this
    drainPendingActions()
    emitSnapshot()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
    mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
      persistFullState()
      stopSelf()
    }
  }

  override fun onDestroy() {
    if (activeService === this) activeService = null
    persistFullState()
    sleepJob?.cancel()
    tickerJob?.cancel()
    autoplayJob?.cancel()
    runtime.streamResolver.cancelAllPrefetches()
    mediaSession?.let { session ->
      player.removeListener(playerListener)
      player.release()
      session.release()
    }
    mediaSession = null
    serviceScope.cancel()
    super.onDestroy()
  }

  private inner class SessionCallback : MediaSession.Callback {
    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      val saved = runtime.stateStore.load()
      if (saved.queue.isEmpty()) {
        return Futures.immediateFuture(
          MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        )
      }
      queueTracks.clear()
      queueTracks.addAll(saved.queue)
      val items = queueTracks.map { buildMediaItem(it) }
      val startIdx = saved.currentIndex.coerceIn(0, items.lastIndex)
      resolveArtworkForWindow(startIdx)
      return Futures.immediateFuture(
        MediaSession.MediaItemsWithStartPosition(items, startIdx, saved.positionMs)
      )
    }
  }

  private val playerListener =
    object : Player.Listener {
      override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && sleepEndOfSong) {
          sleepEndOfSong = false
          sleepDeadlineMs = 0L
          player.pause()
        }
        playbackEndedFlag = false
        lastErrorMessage = ""
        recoveryAttemptsForTrack = 0
        val idx = currentIndexSafe()
        resolveArtworkForWindow(idx)
        scheduleUpcomingPreload(idx)
        maybeTriggerBackgroundAutoplay(idx)
        persistFullState()
        emitSnapshot()
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
          playbackEndedFlag = false
          lastErrorMessage = ""
          startTickerIfNeeded()
        } else {
          tickerJob?.cancel()
          persistPositionOnly()
        }
        emitSnapshot()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
          if (sleepEndOfSong) {
            sleepEndOfSong = false
            sleepDeadlineMs = 0L
          }
          playbackEndedFlag = true
          tickerJob?.cancel()
          if (autoplayEnabled && queueTracks.isNotEmpty()) {
            maybeTriggerBackgroundAutoplay(currentIndexSafe(), autoAdvanceAfterAppend = true)
          }
        } else if (playbackState == Player.STATE_READY) {
          playbackEndedFlag = false
          lastErrorMessage = ""
          recoveryAttemptsForTrack = 0
        }
        emitSnapshot()
      }

      override fun onPlayerError(error: PlaybackException) {
        val idx = currentIndexSafe()
        val track = queueTracks.getOrNull(idx)
        if (track != null) {
          if (lastRecoveryTrackId != track.id) {
            lastRecoveryTrackId = track.id
            recoveryAttemptsForTrack = 0
          }
          if (recoveryAttemptsForTrack < 2) {
            recoveryAttemptsForTrack++
            val resumePos = player.currentPosition.coerceAtLeast(0L)
            runtime.streamResolver.invalidate(track.id)
            serviceScope.launch {
              withContext(Dispatchers.IO) {
                runtime.streamResolver.resolveStream(track.id, audioQuality, forceRefresh = true)
              }
              if (currentIndexSafe() == idx && queueTracks.getOrNull(idx)?.id == track.id) {
                player.seekTo(idx, resumePos)
                player.prepare()
                player.play()
              }
            }
            emitSnapshot()
            return
          }
        }

        lastErrorMessage = error.message ?: "Playback error"
        if (idx in 0 until queueTracks.lastIndex) {
          player.seekTo(idx + 1, 0L)
          player.prepare()
          player.play()
        } else {
          player.pause()
        }
        emitSnapshot()
      }
    }

  fun playQueue(
    tracks: List<PulseTrack>,
    startIndex: Int,
    startPosMs: Long,
    radio: Boolean,
    playWhenReady: Boolean = true,
  ) {
    if (tracks.isEmpty()) return
    queueTracks.clear()
    queueTracks.addAll(tracks)
    radioMode = radio
    shuffleBackupUpcoming = emptyList()
    playbackEndedFlag = false
    lastErrorMessage = ""
    recoveryAttemptsForTrack = 0

    if (shuffleEnabled && !radio && tracks.size > 1) {
      val clamped = startIndex.coerceIn(0, tracks.lastIndex)
      val head = queueTracks.subList(0, clamped + 1).toList()
      val up = queueTracks.subList(clamped + 1, queueTracks.size).toList()
      shuffleBackupUpcoming = up
      val userQueued = up.filter { it.uq != 0 }
      val rest = up.filter { it.uq == 0 }.shuffled()
      queueTracks.clear()
      queueTracks.addAll(head)
      queueTracks.addAll(userQueued)
      queueTracks.addAll(rest)
    }

    val safeIndex = startIndex.coerceIn(0, queueTracks.lastIndex)
    val mediaItems = queueTracks.map { buildMediaItem(it) }
    player.setMediaItems(mediaItems, safeIndex, startPosMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = playWhenReady

    resolveArtworkForWindow(safeIndex)
    scheduleUpcomingPreload(safeIndex)
    maybeTriggerBackgroundAutoplay(safeIndex)
    persistFullState()
    emitSnapshot()
  }

  private fun restoreSavedQueue(
    tracks: List<PulseTrack>,
    startIndex: Int,
    startPosMs: Long,
    autoPlay: Boolean,
  ) {
    queueTracks.clear()
    queueTracks.addAll(tracks)
    val safeIndex = startIndex.coerceIn(0, queueTracks.lastIndex)
    val mediaItems = queueTracks.map { buildMediaItem(it) }
    player.setMediaItems(mediaItems, safeIndex, startPosMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = autoPlay
    resolveArtworkForWindow(safeIndex)
    scheduleUpcomingPreload(safeIndex)
  }

  fun pause() {
    player.pause()
    persistPositionOnly()
    emitSnapshot()
  }

  fun resume() {
    if (queueTracks.isEmpty()) return
    if (player.playbackState == Player.STATE_IDLE) {
      player.prepare()
    }
    if (player.playbackState == Player.STATE_ENDED) {
      player.seekTo(currentIndexSafe(), 0L)
    }
    player.play()
    emitSnapshot()
  }

  fun toggle() {
    if (queueTracks.isEmpty()) return
    if (player.isPlaying) pause() else resume()
  }

  fun seekTo(positionMs: Long) {
    if (queueTracks.isEmpty()) return
    val clamped = positionMs.coerceAtLeast(0L)
    player.seekTo(clamped)
    persistPositionOnly()
    emitSnapshot()
  }

  fun skipNext() {
    if (queueTracks.isEmpty()) return
    val cur = currentIndexSafe()
    if (cur < queueTracks.lastIndex) {
      player.seekTo(cur + 1, 0L)
      player.prepare()
      player.play()
    } else if (autoplayEnabled) {
      maybeTriggerBackgroundAutoplay(cur, autoAdvanceAfterAppend = true)
    }
    emitSnapshot()
  }

  fun skipPrevious() {
    if (queueTracks.isEmpty()) return
    val cur = currentIndexSafe()
    if (cur <= 0 || player.currentPosition > 3_000L) {
      player.seekTo(0L)
    } else {
      player.seekTo(cur - 1, 0L)
      player.prepare()
      player.play()
    }
    emitSnapshot()
  }

  fun jumpToIndex(targetIndex: Int) {
    if (targetIndex !in queueTracks.indices) return
    player.seekTo(targetIndex, 0L)
    player.prepare()
    player.play()
    emitSnapshot()
  }

  fun enqueueTrack(track: PulseTrack, playNext: Boolean) {
    if (queueTracks.isEmpty()) {
      playQueue(listOf(track), 0, 0L, radio = true, playWhenReady = true)
      return
    }
    val cur = currentIndexSafe()
    val flagged = track.copy(uq = 1, pn = if (playNext) 1 else 0, auto = 0)
    var insertPos = cur + 1
    if (playNext) {
      while (insertPos < queueTracks.size && queueTracks[insertPos].pn != 0) insertPos++
    } else {
      while (insertPos < queueTracks.size && queueTracks[insertPos].uq != 0) insertPos++
    }
    queueTracks.add(insertPos, flagged)
    player.addMediaItem(insertPos, buildMediaItem(flagged))
    scheduleUpcomingPreload(cur)
    resolveArtworkForWindow(cur)
    persistFullState()
    emitSnapshot()
  }

  fun appendTracks(tracks: List<PulseTrack>) {
    if (tracks.isEmpty()) return
    val existingIds = queueTracks.mapTo(HashSet()) { it.id }
    val toAdd = tracks.filter { it.id.isNotBlank() && existingIds.add(it.id) }
    if (toAdd.isEmpty()) return
    queueTracks.addAll(toAdd)
    player.addMediaItems(toAdd.map { buildMediaItem(it) })
    val cur = currentIndexSafe()
    scheduleUpcomingPreload(cur)
    persistFullState()
    emitSnapshot()
  }

  fun removeQueueItem(index: Int) {
    val cur = currentIndexSafe()
    if (index !in queueTracks.indices || index == cur) return
    queueTracks.removeAt(index)
    if (index < player.mediaItemCount) {
      player.removeMediaItem(index)
    }
    val updatedCur = currentIndexSafe()
    scheduleUpcomingPreload(updatedCur)
    persistFullState()
    emitSnapshot()
  }

  fun clearUpcoming() {
    val cur = currentIndexSafe()
    if (cur + 1 >= queueTracks.size) return
    val removeCount = queueTracks.size - (cur + 1)
    repeat(removeCount) { queueTracks.removeAt(cur + 1) }
    if (cur + 1 < player.mediaItemCount) {
      player.removeMediaItems(cur + 1, player.mediaItemCount)
    }
    shuffleBackupUpcoming = emptyList()
    scheduleUpcomingPreload(cur)
    persistFullState()
    emitSnapshot()
  }

  fun setShuffle(enabled: Boolean) {
    shuffleEnabled = enabled
    if (queueTracks.isNotEmpty()) {
      val cur = currentIndexSafe()
      val up = queueTracks.subList(cur + 1, queueTracks.size).toList()
      val newUpcoming =
        if (enabled) {
          shuffleBackupUpcoming = up
          val userQueued = up.filter { it.uq != 0 }
          val rest = up.filter { it.uq == 0 }.shuffled()
          userQueued + rest
        } else {
          val backup = shuffleBackupUpcoming
          shuffleBackupUpcoming = emptyList()
          if (backup.isEmpty()) {
            up
          } else {
            val aliveIds = up.mapTo(HashSet()) { it.id }
            val orig = backup.filter { aliveIds.contains(it.id) }
            val origIds = orig.mapTo(HashSet()) { it.id }
            val extras = up.filterNot { origIds.contains(it.id) }
            extras.filter { it.uq != 0 } + orig + extras.filter { it.uq == 0 }
          }
        }

      while (queueTracks.size > cur + 1) queueTracks.removeAt(queueTracks.lastIndex)
      queueTracks.addAll(newUpcoming)
      if (cur + 1 < player.mediaItemCount) {
        player.removeMediaItems(cur + 1, player.mediaItemCount)
      }
      if (newUpcoming.isNotEmpty()) {
        player.addMediaItems(newUpcoming.map { buildMediaItem(it) })
      }
      scheduleUpcomingPreload(cur)
      resolveArtworkForWindow(cur)
    }
    persistFullState()
    emitSnapshot()
  }

  fun setRepeat(enabled: Boolean) {
    repeatEnabled = enabled
    player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    persistFullState()
    emitSnapshot()
  }

  fun setAutoplay(enabled: Boolean) {
    autoplayEnabled = enabled
    if (enabled && queueTracks.isNotEmpty()) {
      maybeTriggerBackgroundAutoplay(currentIndexSafe())
    }
    persistFullState()
    emitSnapshot()
  }

  fun setVolume(percent: Int) {
    volumePercent = percent.coerceIn(0, 100)
    player.volume = volumePercent / 100f
    persistFullState()
    emitSnapshot()
  }

  fun setAudioQuality(quality: String) {
    audioQuality = StreamResolver.normalizeQuality(quality)
    runtime.stateStore.setAudioQuality(audioQuality)
    runtime.downloadManager.setAudioQuality(audioQuality)
    runtime.streamResolver.clearCache()
    scheduleUpcomingPreload(currentIndexSafe())
    emitSnapshot()
  }

  fun setCustomServer(server: String) {
    runtime.stateStore.setCustomServer(server)
    runtime.streamResolver.setCustomServer(server)
    runtime.streamResolver.clearCache()
  }

  fun retryCurrentStream() {
    val idx = currentIndexSafe()
    val track = queueTracks.getOrNull(idx) ?: return
    val pos = player.currentPosition.coerceAtLeast(0L)
    runtime.streamResolver.invalidate(track.id)
    lastErrorMessage = ""
    recoveryAttemptsForTrack = 0
    player.seekTo(idx, pos)
    player.prepare()
    player.play()
    emitSnapshot()
  }

  fun setSleepTimer(minutes: Int, endOfSong: Boolean) {
    sleepJob?.cancel()
    sleepEndOfSong = false
    sleepDeadlineMs = 0L
    if (endOfSong) {
      sleepEndOfSong = true
    } else if (minutes > 0) {
      val durationMs = minutes * 60_000L
      sleepDeadlineMs = System.currentTimeMillis() + durationMs
      sleepJob =
        serviceScope.launch {
          delay(durationMs)
          sleepDeadlineMs = 0L
          sleepEndOfSong = false
          player.pause()
          emitSnapshot()
        }
    }
    emitSnapshot()
  }

  fun getSnapshot(): PlaybackSnapshot {
    val idx = currentIndexSafe()
    val curPosMs = player.currentPosition.coerceAtLeast(0L)
    val durMs =
      player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        ?: ((queueTracks.getOrNull(idx)?.durationSec ?: 0) * 1000L)
    val sleepRem =
      if (sleepDeadlineMs > 0L) (sleepDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
      else 0L
    return PlaybackSnapshot(
      queue = queueTracks.toList(),
      currentIndex = idx,
      isPlaying = player.isPlaying,
      isBuffering = player.playbackState == Player.STATE_BUFFERING,
      playbackEnded = playbackEndedFlag,
      positionSec = curPosMs / 1000.0,
      durationSec = durMs / 1000.0,
      bufferedPercent = player.bufferedPercentage.coerceIn(0, 100),
      shuffleEnabled = shuffleEnabled,
      repeatEnabled = repeatEnabled,
      radioMode = radioMode,
      autoplayEnabled = autoplayEnabled,
      volumePercent = volumePercent,
      sleepRemainingMs = sleepRem,
      sleepEndOfSong = sleepEndOfSong,
      audioQuality = audioQuality,
      errorMessage = lastErrorMessage,
    )
  }

  private fun currentIndexSafe(): Int {
    if (queueTracks.isEmpty()) return -1
    return player.currentMediaItemIndex.coerceIn(0, queueTracks.lastIndex)
  }

  private fun buildMediaItem(
    track: PulseTrack,
    artworkBytes: ByteArray? = null,
  ): MediaItem {
    val cachedBytes = artworkBytes ?: runtime.artworkRepository.getCachedArtworkBytes(track.id)
    val metaBuilder =
      MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist.ifBlank { "Pulse" })
        .setAlbumTitle(track.album.ifBlank { null })

    if (cachedBytes != null) {
      metaBuilder.setArtworkData(cachedBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    } else if (track.thumbUrl.isNotBlank()) {
      metaBuilder.setArtworkUri(Uri.parse(track.thumbUrl))
    } else {
      metaBuilder.setArtworkData(
        runtime.artworkRepository.getFallbackBytes(),
        MediaMetadata.PICTURE_TYPE_FRONT_COVER,
      )
    }

    val mediaUri =
      track.localUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        ?: Uri.parse("pulse://track/${track.id}")

    return MediaItem.Builder()
      .setMediaId(track.id)
      .setUri(mediaUri)
      .setMediaMetadata(metaBuilder.build())
      .build()
  }

  private fun resolveArtworkForWindow(centerIndex: Int) {
    if (queueTracks.isEmpty() || centerIndex !in queueTracks.indices) return
    val windowIndices = (centerIndex..minOf(centerIndex + 2, queueTracks.lastIndex)).toList()
    windowIndices.forEach { targetIdx ->
      val track = queueTracks.getOrNull(targetIdx) ?: return@forEach
      serviceScope.launch {
        val bytes =
          withContext(Dispatchers.IO) { runtime.artworkRepository.getOrFetchArtworkBytes(track) }
        if (targetIdx < player.mediaItemCount) {
          val existingItem = player.getMediaItemAt(targetIdx)
          if (existingItem.mediaId == track.id) {
            val updatedMeta =
              existingItem.mediaMetadata
                .buildUpon()
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            player.replaceMediaItem(
              targetIdx,
              existingItem.buildUpon().setMediaMetadata(updatedMeta).build(),
            )
          }
        }
      }
    }
  }

  private fun scheduleUpcomingPreload(centerIndex: Int) {
    if (queueTracks.isEmpty() || centerIndex !in queueTracks.indices) {
      runtime.streamResolver.cancelAllPrefetches()
      return
    }
    val upcomingIds =
      ((centerIndex + 1)..minOf(centerIndex + 3, queueTracks.lastIndex)).mapNotNull { i ->
        queueTracks.getOrNull(i)?.id
      }
    runtime.streamResolver.schedulePrefetch(upcomingIds, audioQuality)
  }

  private fun maybeTriggerBackgroundAutoplay(
    currentIndex: Int,
    autoAdvanceAfterAppend: Boolean = false,
  ) {
    if (!autoplayEnabled || queueTracks.isEmpty()) return
    val remaining = queueTracks.lastIndex - currentIndex
    if (remaining > 2 && !autoAdvanceAfterAppend) return
    if (autoplayJob?.isActive == true) return
    val seed = queueTracks.lastOrNull() ?: return
    if (seed.id.startsWith("local_")) return

    autoplayJob =
      serviceScope.launch {
        val related =
          withContext(Dispatchers.IO) { runtime.streamResolver.fetchRelatedTracks(seed.id) }
        val existingIds = queueTracks.mapTo(HashSet()) { it.id }
        val toAdd = related.filter { it.id.isNotBlank() && existingIds.add(it.id) }.take(15)
        if (toAdd.isNotEmpty()) {
          val insertStart = queueTracks.size
          queueTracks.addAll(toAdd)
          player.addMediaItems(toAdd.map { buildMediaItem(it) })
          scheduleUpcomingPreload(currentIndexSafe())
          persistFullState()
          if (autoAdvanceAfterAppend && insertStart < player.mediaItemCount) {
            player.seekTo(insertStart, 0L)
            player.prepare()
            player.play()
          }
          emitSnapshot()
        }
      }
  }

  private fun startTickerIfNeeded() {
    tickerJob?.cancel()
    tickerJob =
      serviceScope.launch {
        while (true) {
          delay(250L)
          val now = System.currentTimeMillis()
          if (now - lastPositionPersistMs >= 5_000L) {
            lastPositionPersistMs = now
            persistPositionOnly()
          }
          emitSnapshot()
        }
      }
  }

  private fun persistFullState() {
    runtime.stateStore.saveQueueState(
      queue = queueTracks,
      currentIndex = currentIndexSafe().coerceAtLeast(0),
      positionMs = player.currentPosition.coerceAtLeast(0L),
      shuffleEnabled = shuffleEnabled,
      repeatEnabled = repeatEnabled,
      radioMode = radioMode,
      autoplayEnabled = autoplayEnabled,
      volumePercent = volumePercent,
      shuffleBackupUpcoming = shuffleBackupUpcoming,
    )
  }

  private fun persistPositionOnly() {
    runtime.stateStore.savePositionOnly(
      currentIndex = currentIndexSafe().coerceAtLeast(0),
      positionMs = player.currentPosition.coerceAtLeast(0L),
    )
  }

  private fun emitSnapshot() {
    if (listeners.isEmpty()) return
    val snap = getSnapshot()
    listeners.forEach { it.onPlaybackSnapshot(snap) }
  }

  companion object {
    @Volatile private var activeService: PulsePlaybackService? = null
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val pendingActions = mutableListOf<(PulsePlaybackService) -> Unit>()

    fun addListener(listener: Listener) {
      listeners.add(listener)
      activeService?.let { listener.onPlaybackSnapshot(it.getSnapshot()) }
    }

    fun removeListener(listener: Listener) {
      listeners.remove(listener)
    }

    fun getCurrentService(): PulsePlaybackService? = activeService

    fun dispatchOrQueue(action: (PulsePlaybackService) -> Unit) {
      val svc = activeService
      if (svc != null) {
        svc.serviceScope.launch { action(svc) }
      } else {
        synchronized(pendingActions) {
          if (pendingActions.size > 20) pendingActions.removeAt(0)
          pendingActions.add(action)
        }
      }
    }

    private fun drainPendingActions() {
      val svc = activeService ?: return
      val batch: List<(PulsePlaybackService) -> Unit>
      synchronized(pendingActions) {
        batch = pendingActions.toList()
        pendingActions.clear()
      }
      batch.forEach { act -> svc.serviceScope.launch { act(svc) } }
    }
  }
}
