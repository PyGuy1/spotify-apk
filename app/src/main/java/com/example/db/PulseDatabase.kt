package com.example.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.DownloadedSongRecord
import com.example.model.PulseTrack
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloaded_songs")
data class DownloadedSongEntity(
  @PrimaryKey val trackId: String,
  val title: String,
  val artist: String,
  val album: String,
  val durationSec: Int?,
  val thumbUrl: String,
  val lyrics: String,
  val localUri: String,
  val fileName: String,
  val mimeType: String,
  val bitrate: Int,
  val itag: Int,
  val sizeBytes: Long,
  val addedAtMs: Long,
  val isAppManaged: Boolean,
  val sourceType: String,
  val cacheStatus: String,
  val updatedAtMs: Long = System.currentTimeMillis(),
) {
  fun toRecord(): DownloadedSongRecord =
    DownloadedSongRecord(
      track =
        PulseTrack(
          id = trackId,
          title = title,
          artist = artist,
          album = album,
          durationSec = durationSec,
          thumbUrl = thumbUrl,
          localUri = localUri,
          lyrics = lyrics,
          sourceType = sourceType,
          mimeType = mimeType,
          bitrate = bitrate,
          itag = itag,
        ),
      localUri = localUri,
      fileName = fileName,
      mimeType = mimeType,
      sizeBytes = sizeBytes,
      addedAtMs = addedAtMs,
      isAppManaged = isAppManaged,
      bitrate = bitrate,
      itag = itag,
      cacheStatus = cacheStatus,
    )

  companion object {
    fun fromRecord(record: DownloadedSongRecord): DownloadedSongEntity =
      DownloadedSongEntity(
        trackId = record.track.id,
        title = record.track.title,
        artist = record.track.artist,
        album = record.track.album,
        durationSec = record.track.durationSec,
        thumbUrl = record.track.thumbUrl,
        lyrics = record.track.lyrics,
        localUri = record.localUri.ifBlank { record.track.localUri },
        fileName = record.fileName,
        mimeType = record.mimeType.ifBlank { record.track.mimeType.ifBlank { "audio/mp4" } },
        bitrate = if (record.bitrate > 0) record.bitrate else record.track.bitrate,
        itag = if (record.itag > 0) record.itag else record.track.itag,
        sizeBytes = record.sizeBytes,
        addedAtMs = record.addedAtMs,
        isAppManaged = record.isAppManaged,
        sourceType = record.track.sourceType.ifBlank { "youtube" },
        cacheStatus = record.cacheStatus.ifBlank { "completed" },
      )
  }
}

@Entity(tableName = "song_formats")
data class SongFormatEntity(
  @PrimaryKey val trackId: String,
  val itag: Int,
  val mimeType: String,
  val codecs: String,
  val bitrate: Int,
  val sampleRate: Int,
  val contentLength: Long,
  val loudnessDb: Double?,
  val engineUsed: String,
  val updatedAtMs: Long = System.currentTimeMillis(),
)

@Dao
interface DownloadedSongDao {
  @Query("SELECT * FROM downloaded_songs ORDER BY addedAtMs DESC")
  fun observeAll(): Flow<List<DownloadedSongEntity>>

  @Query("SELECT * FROM downloaded_songs ORDER BY addedAtMs DESC")
  fun getAllSync(): List<DownloadedSongEntity>

  @Query("SELECT * FROM downloaded_songs WHERE trackId = :trackId LIMIT 1")
  fun findByTrackIdSync(trackId: String): DownloadedSongEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun upsertSync(entity: DownloadedSongEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun upsertAllSync(entities: List<DownloadedSongEntity>)

  @Query("DELETE FROM downloaded_songs WHERE trackId = :trackId")
  fun deleteByTrackIdSync(trackId: String): Int

  @Query("DELETE FROM downloaded_songs WHERE localUri = :localUri")
  fun deleteByLocalUriSync(localUri: String): Int

  @Query("DELETE FROM downloaded_songs")
  fun deleteAllSync()
}

@Dao
interface SongFormatDao {
  @Query("SELECT * FROM song_formats WHERE trackId = :trackId LIMIT 1")
  fun findByTrackIdSync(trackId: String): SongFormatEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun upsertSync(entity: SongFormatEntity)
}

@Database(
  entities = [DownloadedSongEntity::class, SongFormatEntity::class],
  version = 2,
  exportSchema = false,
)
abstract class PulseDatabase : RoomDatabase() {
  abstract fun downloadedSongDao(): DownloadedSongDao
  abstract fun songFormatDao(): SongFormatDao

  companion object {
    @Volatile private var instance: PulseDatabase? = null

    val MIGRATION_1_2: Migration =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "ALTER TABLE downloaded_songs ADD COLUMN cacheStatus TEXT NOT NULL DEFAULT 'completed'"
          )
        }
      }

    fun get(context: Context): PulseDatabase =
      instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
              context.applicationContext,
              PulseDatabase::class.java,
              "pymusic_pulse.db",
            )
              .addMigrations(MIGRATION_1_2)
              .fallbackToDestructiveMigration(dropAllTables = true)
              .allowMainThreadQueries()
              .build()
              .also { instance = it }
        }
  }
}
