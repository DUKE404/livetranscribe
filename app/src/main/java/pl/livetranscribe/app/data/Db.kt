package pl.livetranscribe.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object SessionStatus {
    const val RECORDING = "RECORDING"
    const val PAUSED = "PAUSED"
    const val PROCESSING = "PROCESSING"
    /** Wczytywanie pliku nagrania (np. z dyktafonu) – fragmenty jeszcze powstają. */
    const val IMPORTING = "IMPORTING"
    const val DONE = "DONE"
}

object ChunkStatus {
    const val PENDING = "PENDING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val status: String,
    /** "cloud" albo "local" – gdzie przetwarzany jest dźwięk tej sesji. */
    val engine: String,
    /** Tekst po ręcznej edycji; null = tekst składany na bieżąco z fragmentów. */
    val editedText: String? = null,
    val pdfPath: String? = null,
    /** Notatka AI (JSON z [pl.livetranscribe.app.engine.MeetingNote]) albo null. */
    val summaryJson: String? = null,
)

@Entity(
    tableName = "chunks",
    indices = [Index("sessionId")],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val audioPath: String?,
    val status: String,
    val text: String = "",
    val segmentsJson: String = "",
    val attempts: Int = 0,
    val error: String? = null,
)

@Dao
interface AppDao {
    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun sessionFlow(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun sessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions")
    suspend fun allSessions(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE sessions SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: Long, durationMs: Long)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE sessions SET editedText = :text WHERE id = :id")
    suspend fun setEditedText(id: Long, text: String?)

    @Query("UPDATE sessions SET summaryJson = :json WHERE id = :id")
    suspend fun setSummary(id: Long, json: String?)

    @Query("UPDATE sessions SET pdfPath = :path WHERE id = :id")
    suspend fun setPdfPath(id: Long, path: String?)

    @Insert
    suspend fun insertChunk(c: ChunkEntity): Long

    @Update
    suspend fun updateChunk(c: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE sessionId = :sessionId ORDER BY idx")
    fun chunksFlow(sessionId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE sessionId = :sessionId ORDER BY idx")
    suspend fun chunks(sessionId: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE status = 'PENDING' ORDER BY sessionId, idx LIMIT 1")
    suspend fun nextPending(): ChunkEntity?

    @Query("SELECT COUNT(*) FROM chunks WHERE sessionId = :sessionId AND status = 'PENDING'")
    suspend fun pendingCount(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE sessionId = :sessionId")
    suspend fun chunkCount(sessionId: Long): Int

    @Query("SELECT MAX(endMs) FROM chunks WHERE sessionId = :sessionId")
    suspend fun lastEndMs(sessionId: Long): Long?

    @Query("UPDATE chunks SET status = 'PENDING', attempts = 0, error = NULL WHERE sessionId = :sessionId AND status = 'FAILED' AND audioPath IS NOT NULL")
    suspend fun retryFailed(sessionId: Long)

    @Query("SELECT text FROM chunks WHERE sessionId = :sessionId AND idx < :idx AND status = 'DONE' AND text != '' ORDER BY idx DESC LIMIT 1")
    suspend fun previousText(sessionId: Long, idx: Int): String?
}

/** Wersja 2: notatka AI przy dokumencie. Istniejące transkrypcje zostają nietknięte. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN summaryJson TEXT")
    }
}

@Database(entities = [SessionEntity::class, ChunkEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
}
