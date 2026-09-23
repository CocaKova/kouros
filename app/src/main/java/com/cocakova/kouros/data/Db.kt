package com.cocakova.kouros.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** How a request to this server authenticates. The secret itself is never stored here. */
enum class AuthKind { NONE, BEARER, BASIC, HEADER }

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val authKind: AuthKind = AuthKind.NONE,
    /** Basic-auth user name, or the header name for [AuthKind.HEADER]. */
    val authUser: String? = null,
    /** Stable per-server socket identity so the server keeps routing our events after reconnects. */
    val clientId: String,
    /** A plain-http URL to a public address was explicitly allowed by the user. */
    val allowInsecure: Boolean = false,
    /** Optional user-defined power controls (JSON, see PowerControl). */
    val powerJson: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long? = null,
)

@Entity(tableName = "workflows", indices = [Index("serverId")])
data class WorkflowEntity(
    /** "<serverId>|<source>|<path>" — source is "userdata" or "local". */
    @PrimaryKey val key: String,
    val serverId: String,
    val source: String,
    val path: String,
    val name: String,
    val json: String? = null,
    val modified: Double? = null,
    val lastOpenedAt: Long? = null,
    val pinned: Boolean = false,
    /** Field pins/labels/order the user chose (JSON). */
    val formConfig: String? = null,
    /** Values from the last run (JSON: field key → value), restored when the form opens. */
    val lastValues: String? = null,
)

enum class RunState { SUBMITTING, QUEUED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED, LOST, REJECTED }

@Entity(tableName = "runs", indices = [Index("serverId"), Index("createdAt")])
data class RunEntity(
    /** The prompt id — chosen by the app before submitting. */
    @PrimaryKey val promptId: String,
    val serverId: String,
    val workflowKey: String?,
    val workflowName: String,
    val promptJson: String,
    val valuesJson: String?,
    val state: RunState,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    /** Output file refs as JSON: [{"node","kind","filename","subfolder","type"}]. */
    val outputsJson: String? = null,
    val favorite: Boolean = false,
    val seen: Boolean = false,
)

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder, createdAt") fun all(): Flow<List<ServerEntity>>
    @Query("SELECT * FROM servers ORDER BY sortOrder, createdAt") suspend fun list(): List<ServerEntity>
    @Query("SELECT * FROM servers WHERE id = :id") suspend fun get(id: String): ServerEntity?
    @Upsert suspend fun upsert(s: ServerEntity)
    @Query("DELETE FROM servers WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE servers SET lastSeenAt = :at WHERE id = :id") suspend fun seen(id: String, at: Long)
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows WHERE serverId = :serverId ORDER BY pinned DESC, COALESCE(lastOpenedAt, 0) DESC, name COLLATE NOCASE")
    fun forServer(serverId: String): Flow<List<WorkflowEntity>>
    @Query("SELECT * FROM workflows WHERE `key` = :key") suspend fun get(key: String): WorkflowEntity?
    @Query("SELECT * FROM workflows WHERE `key` = :key") fun observe(key: String): Flow<WorkflowEntity?>
    @Upsert suspend fun upsert(w: WorkflowEntity)
    @Upsert suspend fun upsertAll(w: List<WorkflowEntity>)
    @Query("DELETE FROM workflows WHERE serverId = :serverId AND source = 'userdata' AND `key` NOT IN (:keep)")
    suspend fun pruneUserdata(serverId: String, keep: List<String>)
    @Query("UPDATE workflows SET lastOpenedAt = :at WHERE `key` = :key") suspend fun opened(key: String, at: Long)
    @Query("UPDATE workflows SET pinned = :pinned WHERE `key` = :key") suspend fun pin(key: String, pinned: Boolean)
    @Query("UPDATE workflows SET lastValues = :values WHERE `key` = :key") suspend fun saveValues(key: String, values: String)
    @Query("UPDATE workflows SET formConfig = :config WHERE `key` = :key") suspend fun saveConfig(key: String, config: String)
    @Query("DELETE FROM workflows WHERE `key` = :key") suspend fun delete(key: String)
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs ORDER BY createdAt DESC LIMIT :limit") fun recent(limit: Int = 500): Flow<List<RunEntity>>
    @Query("SELECT * FROM runs WHERE state IN ('SUBMITTING','QUEUED','RUNNING') ORDER BY createdAt")
    fun active(): Flow<List<RunEntity>>
    @Query("SELECT * FROM runs WHERE state IN ('SUBMITTING','QUEUED','RUNNING') ORDER BY createdAt")
    suspend fun activeList(): List<RunEntity>
    @Query("SELECT * FROM runs WHERE promptId = :id") suspend fun get(id: String): RunEntity?
    @Query("SELECT * FROM runs WHERE promptId = :id") fun observe(id: String): Flow<RunEntity?>
    @Query("SELECT * FROM runs WHERE workflowKey = :key ORDER BY createdAt DESC LIMIT 1") suspend fun lastFor(key: String): RunEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(r: RunEntity): Long
    @Update suspend fun update(r: RunEntity)
    @Query("UPDATE runs SET favorite = :fav WHERE promptId = :id") suspend fun favorite(id: String, fav: Boolean)
    @Query("UPDATE runs SET seen = 1 WHERE promptId = :id") suspend fun markSeen(id: String)
    @Query("DELETE FROM runs WHERE promptId = :id") suspend fun delete(id: String)
}

@Database(entities = [ServerEntity::class, WorkflowEntity::class, RunEntity::class], version = 1, exportSchema = true)
abstract class KourosDb : RoomDatabase() {
    abstract fun servers(): ServerDao
    abstract fun workflows(): WorkflowDao
    abstract fun runs(): RunDao

    companion object {
        fun open(context: Context): KourosDb =
            Room.databaseBuilder(context, KourosDb::class.java, "kouros.db").build()
    }
}
