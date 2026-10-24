package com.habitsync.gtasks.data.local

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

@Entity(tableName = "task_sync_links")
data class TaskSyncEntity(
    @PrimaryKey val habiticaTaskId: String,
    val googleTaskId: String,
    val googleTaskListId: String,
    val taskType: String, // "todo" or "daily"
    val lastSyncedTitle: String,
    val lastSyncedNotes: String,
    val lastSyncedDueDate: String?,
    val lastCompletedState: Boolean,
    val lastHabiticaUpdatedMs: Long,
    val lastGoogleUpdatedMs: Long,
    val isUnlinked: Boolean = false
)

@Entity(tableName = "subtask_sync_links", primaryKeys = ["parentHabiticaId", "habiticaChecklistItemId"])
data class SubtaskSyncEntity(
    val parentHabiticaId: String,
    val habiticaChecklistItemId: String,
    val googleSubtaskId: String,
    val googleTaskListId: String,
    val lastTitle: String,
    val lastCompleted: Boolean
)

@Entity(tableName = "sync_audit_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val direction: String, // "G->H", "H->G", "BOTH", "ERROR"
    val message: String
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM task_sync_links WHERE isUnlinked = 0")
    suspend fun getAllActiveLinks(): List<TaskSyncEntity>

    @Query("SELECT * FROM task_sync_links WHERE habiticaTaskId = :habId LIMIT 1")
    suspend fun getByHabiticaId(habId: String): TaskSyncEntity?

    @Query("SELECT * FROM task_sync_links WHERE googleTaskId = :gId LIMIT 1")
    suspend fun getByGoogleId(gId: String): TaskSyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskLink(entity: TaskSyncEntity)

    @Query("DELETE FROM task_sync_links WHERE habiticaTaskId = :habId")
    suspend fun deleteTaskLink(habId: String)

    @Query("SELECT * FROM subtask_sync_links WHERE parentHabiticaId = :parentHabId")
    suspend fun getSubtaskLinks(parentHabId: String): List<SubtaskSyncEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubtaskLink(entity: SubtaskSyncEntity)

    @Query("DELETE FROM subtask_sync_links WHERE parentHabiticaId = :parentHabId AND habiticaChecklistItemId = :itemId")
    suspend fun deleteSubtaskLink(parentHabId: String, itemId: String)

    @Insert
    suspend fun insertLog(log: SyncLogEntity)

    @Query("SELECT * FROM sync_audit_logs ORDER BY timestampMs DESC LIMIT 100")
    suspend fun getRecentLogs(): List<SyncLogEntity>

    @Query("DELETE FROM sync_audit_logs")
    suspend fun clearLogs()
}

@Database(
    entities = [TaskSyncEntity::class, SubtaskSyncEntity::class, SyncLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "habitsync_two_way.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
