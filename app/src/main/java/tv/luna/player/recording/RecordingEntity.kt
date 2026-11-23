package tv.luna.player.recording

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class RecStatus { SCHEDULED, RECORDING, DONE, STOPPED, FAILED, SKIPPED, MISSED }
enum class RecStorage { INTERNAL, USB, SMB }

/** One recording, scheduled or made. No foreign keys: rows (and their files) outlive deleted playlists and channels. */
@Entity(tableName = "recording", indices = [Index("status"), Index("scheduledStart")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val channelId: Long?,
    val channelName: String,
    val channelLogo: String?,
    val title: String,
    val description: String?,
    /** With padding. */
    val scheduledStart: Long,
    val scheduledEnd: Long,
    /** The guide programme's start (null for Record now): marks the programme as scheduled in menus and the guide. */
    val programmeStart: Long?,
    val actualStart: Long? = null,
    val actualEnd: Long? = null,
    val status: RecStatus = RecStatus.SCHEDULED,
    val statusReason: String? = null,
    /** Set when recording starts: Save to may change in between. */
    val storage: RecStorage? = null,
    /** A file path, or smb://server/share/path. */
    val location: String? = null,
    val bytes: Long = 0,
    val positionMs: Long = 0,
    /** "Keep watching" was picked at the 1-minute warning. */
    val skipAsked: Boolean = false,
    /** The series rule that scheduled this one (null: a single episode). */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val seriesId: Long? = null,
)

/** "Record series": every showing of [title] on [channelId] is scheduled as the guide lists it, until the rule is stopped. */
@Entity(tableName = "series_rule", indices = [Index("channelId")])
data class SeriesRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val channelId: Long,
    val channelName: String,
    val title: String,
)

@Dao
interface RecordingDao {
    @Insert suspend fun insert(r: RecordingEntity): Long
    @Update suspend fun update(r: RecordingEntity)
    @Query("SELECT * FROM recording WHERE id = :id") suspend fun get(id: Long): RecordingEntity?
    @Query("DELETE FROM recording WHERE id = :id") suspend fun delete(id: Long)

    /** Recording and scheduled first (soonest first), then the rest newest first. */
    @Query("""SELECT * FROM recording ORDER BY CASE WHEN status IN ('RECORDING','SCHEDULED') THEN 0 ELSE 1 END,
        CASE WHEN status IN ('RECORDING','SCHEDULED') THEN scheduledStart ELSE -COALESCE(actualStart, scheduledStart) END""")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording WHERE status IN (:s)") suspend fun withStatus(vararg s: RecStatus): List<RecordingEntity>
    @Query("SELECT * FROM recording WHERE channelId = :channelId AND status IN (:statuses) ORDER BY scheduledStart")
    suspend fun onChannel(channelId: Long, statuses: List<RecStatus>): List<RecordingEntity>
    @Insert suspend fun insertRule(r: SeriesRuleEntity): Long
    @Query("SELECT * FROM series_rule") suspend fun rules(): List<SeriesRuleEntity>
    @Query("SELECT * FROM series_rule") fun observeRules(): Flow<List<SeriesRuleEntity>>
    @Query("DELETE FROM series_rule WHERE id = :id") suspend fun deleteRule(id: Long)
    @Query("SELECT * FROM recording WHERE seriesId = :id") suspend fun inSeries(id: Long): List<RecordingEntity>
    @Query("SELECT COUNT(*) FROM recording WHERE channelId = :channelId AND programmeStart = :start") suspend fun countProgramme(channelId: Long, start: Long): Int
    @Query("UPDATE recording SET positionMs = :ms WHERE id = :id") suspend fun setPosition(id: Long, ms: Long)
    @Query("UPDATE recording SET bytes = :bytes WHERE id = :id") suspend fun setBytes(id: Long, bytes: Long)
}
