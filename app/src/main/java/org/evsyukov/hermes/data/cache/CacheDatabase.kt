package org.evsyukov.hermes.data.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/** An answer of the server kept on the phone: [size] counts the bytes of its text, [used] is when it was last read or written. */
@Entity(tableName = "responses", primaryKeys = ["server", "request"], indices = [Index("used")])
data class CachedResponse(val server: String, val request: String, val size: Long, val used: Long)

/**
 * A piece of the text of a [CachedResponse]. Android reads a row through a window of 2 MB, and the
 * history of a long chat is larger, so the text is stored in pieces that each fit.
 */
@Entity(tableName = "parts", primaryKeys = ["server", "request", "part"])
data class ResponsePart(val server: String, val request: String, val part: Int, val text: String)

/** A response as the eviction sees it, without its text. */
data class ResponseSize(val server: String, val request: String, val size: Long)

@Dao
abstract class ResponseDao {
    @Query("SELECT text FROM parts WHERE server = :server AND request = :request ORDER BY part")
    abstract suspend fun parts(server: String, request: String): List<String>

    @Query("SELECT * FROM responses WHERE server = :server AND request = :request")
    abstract suspend fun response(server: String, request: String): CachedResponse?

    @Query("UPDATE responses SET used = :used WHERE server = :server AND request = :request")
    abstract suspend fun touch(server: String, request: String, used: Long)

    @Upsert
    protected abstract suspend fun upsert(response: CachedResponse)

    @Insert
    protected abstract suspend fun insertParts(parts: List<ResponsePart>)

    @Query("DELETE FROM parts WHERE server = :server AND request = :request")
    protected abstract suspend fun deleteParts(server: String, request: String)

    @Query("DELETE FROM responses WHERE server = :server AND request = :request")
    protected abstract suspend fun deleteResponse(server: String, request: String)

    @Transaction
    open suspend fun put(response: CachedResponse, parts: List<String>) {
        deleteParts(response.server, response.request)
        upsert(response)
        insertParts(parts.mapIndexed { i, text -> ResponsePart(response.server, response.request, i, text) })
    }

    @Transaction
    open suspend fun delete(server: String, request: String) {
        deleteParts(server, request)
        deleteResponse(server, request)
    }

    @Query("SELECT COALESCE(SUM(size), 0) FROM responses")
    abstract suspend fun total(): Long

    /** The responses from the least recently used on. */
    @Query("SELECT server, request, size FROM responses ORDER BY used, request")
    abstract suspend fun byUse(): List<ResponseSize>

    @Query("DELETE FROM parts WHERE server = :server AND request LIKE :prefix || '%' ESCAPE '\\'")
    protected abstract suspend fun deletePartsLike(server: String, prefix: String)

    @Query("DELETE FROM responses WHERE server = :server AND request LIKE :prefix || '%' ESCAPE '\\'")
    protected abstract suspend fun deleteResponsesLike(server: String, prefix: String)

    /** Deletes the responses of [server] whose request starts with [prefix]; `%` and `_` in it are taken literally. */
    @Transaction
    open suspend fun deleteStarting(server: String, prefix: String) {
        val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        deletePartsLike(server, escaped)
        deleteResponsesLike(server, escaped)
    }

    @Query("SELECT DISTINCT server FROM responses")
    abstract suspend fun servers(): List<String>

    @Query("DELETE FROM parts")
    protected abstract suspend fun deleteAllParts()

    @Query("DELETE FROM responses")
    protected abstract suspend fun deleteAllResponses()

    @Transaction
    open suspend fun clear() {
        deleteAllParts()
        deleteAllResponses()
    }
}

@Database(entities = [CachedResponse::class, ResponsePart::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun responses(): ResponseDao

    companion object {
        /** The cache can always be refilled from the server, so a new schema simply starts it empty. */
        fun open(context: Context): CacheDatabase =
            Room.databaseBuilder(context, CacheDatabase::class.java, "cache.db").fallbackToDestructiveMigration(dropAllTables = true).build()
    }
}
