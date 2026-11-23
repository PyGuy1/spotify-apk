package com.example.playback

import android.content.Context
import com.example.model.PlaybackEngineMode
import com.example.model.PulseTrack
import com.example.model.SelfHostedServerConfig

data class PersistedPlaybackState(
  val queue: List<PulseTrack>,
  val currentIndex: Int,
  val positionMs: Long,
  val shuffleEnabled: Boolean,
  val repeatEnabled: Boolean,
  val radioMode: Boolean,
  val autoplayEnabled: Boolean,
  val volumePercent: Int,
  val audioQuality: String,
  val wifiQuality: String,
  val cellularQuality: String,
  val engineMode: PlaybackEngineMode,
  val customServer: String,
  val selfHostedConfig: SelfHostedServerConfig,
  val shuffleBackupUpcoming: List<PulseTrack> = emptyList(),
)

class PlaybackStateStore(context: Context) {
  private val prefs = context.getSharedPreferences("pulse_playback_state", Context.MODE_PRIVATE)

  fun saveQueueState(
    queue: List<PulseTrack>,
    currentIndex: Int,
    positionMs: Long,
    shuffleEnabled: Boolean,
    repeatEnabled: Boolean,
    radioMode: Boolean,
    autoplayEnabled: Boolean,
    volumePercent: Int,
    shuffleBackupUpcoming: List<PulseTrack>,
  ) {
    val start = maxOf(0, currentIndex - 100)
    val end = minOf(queue.size, currentIndex + 160)
    val sliced = if (queue.isEmpty()) emptyList() else queue.subList(start, end)
    val adjustedIndex = if (sliced.isEmpty()) 0 else (currentIndex - start).coerceIn(0, sliced.size - 1)

    prefs
      .edit()
      .putString(KEY_QUEUE, PulseTrack.listToJsonArray(sliced).toString())
      .putInt(KEY_INDEX, adjustedIndex)
      .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
      .putBoolean(KEY_SHUFFLE, shuffleEnabled)
      .putBoolean(KEY_REPEAT, repeatEnabled)
      .putBoolean(KEY_RADIO, radioMode)
      .putBoolean(KEY_AUTOPLAY, autoplayEnabled)
      .putInt(KEY_VOLUME, volumePercent.coerceIn(0, 100))
      .putString(KEY_SHUF_BACKUP, PulseTrack.listToJsonArray(shuffleBackupUpcoming).toString())
      .apply()
  }

  fun savePositionOnly(currentIndex: Int, positionMs: Long) {
    prefs
      .edit()
      .putInt(KEY_INDEX, currentIndex.coerceAtLeast(0))
      .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
      .apply()
  }

  fun setAudioQuality(quality: String) {
    prefs.edit().putString(KEY_QUALITY, quality).apply()
  }

  fun getAudioQuality(): String = prefs.getString(KEY_QUALITY, "normal").orEmpty().ifBlank { "normal" }

  fun setWifiQuality(quality: String) {
    prefs.edit().putString(KEY_WIFI_QUALITY, quality).apply()
  }

  fun getWifiQuality(): String =
    prefs.getString(KEY_WIFI_QUALITY, null).orEmpty().ifBlank { getAudioQuality() }

  fun setCellularQuality(quality: String) {
    prefs.edit().putString(KEY_CELLULAR_QUALITY, quality).apply()
  }

  fun getCellularQuality(): String =
    prefs.getString(KEY_CELLULAR_QUALITY, null).orEmpty().ifBlank { "normal" }

  fun setEngineMode(mode: PlaybackEngineMode) {
    prefs.edit().putString(KEY_ENGINE_MODE, mode.wireName).apply()
  }

  fun getEngineMode(): PlaybackEngineMode =
    PlaybackEngineMode.fromWire(prefs.getString(KEY_ENGINE_MODE, PlaybackEngineMode.AUTO.wireName))

  fun setCustomServer(server: String) {
    prefs.edit().putString(KEY_CUSTOM_SERVER, server.trim()).apply()
  }

  fun getCustomServer(): String = prefs.getString(KEY_CUSTOM_SERVER, "").orEmpty()

  fun setSelfHostedConfig(config: SelfHostedServerConfig) {
    prefs.edit().putString(KEY_SELF_HOSTED_JSON, config.toJson(includeSecret = true).toString()).apply()
  }

  fun getSelfHostedConfig(): SelfHostedServerConfig =
    SelfHostedServerConfig.fromJson(prefs.getString(KEY_SELF_HOSTED_JSON, null))

  fun load(): PersistedPlaybackState {
    val queue = PulseTrack.listFromJsonArray(prefs.getString(KEY_QUEUE, null))
    val idx =
      if (queue.isEmpty()) 0
      else prefs.getInt(KEY_INDEX, 0).coerceIn(0, queue.lastIndex)
    val backup = PulseTrack.listFromJsonArray(prefs.getString(KEY_SHUF_BACKUP, null))
    return PersistedPlaybackState(
      queue = queue,
      currentIndex = idx,
      positionMs = prefs.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
      shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
      repeatEnabled = prefs.getBoolean(KEY_REPEAT, false),
      radioMode = prefs.getBoolean(KEY_RADIO, false),
      autoplayEnabled = prefs.getBoolean(KEY_AUTOPLAY, true),
      volumePercent = prefs.getInt(KEY_VOLUME, 80).coerceIn(0, 100),
      audioQuality = getAudioQuality(),
      wifiQuality = getWifiQuality(),
      cellularQuality = getCellularQuality(),
      engineMode = getEngineMode(),
      customServer = getCustomServer(),
      selfHostedConfig = getSelfHostedConfig(),
      shuffleBackupUpcoming = backup,
    )
  }

  companion object {
    private const val KEY_QUEUE = "queue_json"
    private const val KEY_INDEX = "current_index"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"
    private const val KEY_RADIO = "radio"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_VOLUME = "volume"
    private const val KEY_QUALITY = "audio_quality"
    private const val KEY_WIFI_QUALITY = "wifi_audio_quality"
    private const val KEY_CELLULAR_QUALITY = "cellular_audio_quality"
    private const val KEY_ENGINE_MODE = "engine_mode"
    private const val KEY_CUSTOM_SERVER = "custom_server"
    private const val KEY_SELF_HOSTED_JSON = "self_hosted_json"
    private const val KEY_SHUF_BACKUP = "shuffle_backup"
  }
}
