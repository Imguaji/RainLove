package com.rainlove.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One process-wide queue preserves event order across the UI and monitoring service. */
class TriggerEventLog private constructor(context: Context) {
    private val database = EventDatabase(context.applicationContext)
    private val queue = Executors.newSingleThreadExecutor()

    fun record(event: TriggerEventRecord) {
        queue.execute {
            runCatching {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    db.insertOrThrow("events", null, ContentValues().apply {
                        put("timestamp_ms", event.timestampMs)
                        put("source", event.source)
                        put("type", event.type.name)
                        put("message", event.message.take(2_000))
                        event.bpm?.let { put("bpm", it) } ?: putNull("bpm")
                    })
                    db.execSQL("DELETE FROM events WHERE _id NOT IN (SELECT _id FROM events ORDER BY _id DESC LIMIT $MAX_EVENTS)")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }.onFailure { Log.w("RainLoveEvents", "Failed to store trigger event", it) }
        }
    }

    suspend fun latest(): List<TriggerEventRecord> = submit { readEvents("DESC", 100) }

    suspend fun clear(): Int = submit { database.writableDatabase.delete("events", null, null) }

    suspend fun exportCsv(openOutput: () -> OutputStream): Unit = submit {
        openOutput().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("timestamp_utc,source,event,bpm,message\n")
            readEvents("ASC", MAX_EVENTS).forEach { writer.write(it.csvRow()) }
        }
    }

    private fun readEvents(order: String, limit: Int): List<TriggerEventRecord> {
        val records = mutableListOf<TriggerEventRecord>()
        database.readableDatabase.query(
            "events", arrayOf("timestamp_ms", "source", "type", "message", "bpm"),
            null, null, null, null, "_id $order", limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records += TriggerEventRecord(
                    cursor.getLong(0), cursor.getString(1),
                    TriggerEventType.valueOf(cursor.getString(2)), cursor.getString(3),
                    if (cursor.isNull(4)) null else cursor.getInt(4),
                )
            }
        }
        return records
    }

    private suspend fun <T> submit(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        queue.execute {
            try { continuation.resume(block()) }
            catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }

    private class EventDatabase(context: Context) : SQLiteOpenHelper(context, "trigger_events.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL, source TEXT NOT NULL, type TEXT NOT NULL, message TEXT NOT NULL, bpm INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val MAX_EVENTS = 2_000
        @Volatile private var instance: TriggerEventLog? = null
        fun get(context: Context): TriggerEventLog = instance ?: synchronized(this) {
            instance ?: TriggerEventLog(context).also { instance = it }
        }
    }
}
