package com.rainlove.app.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant

data class HeartRateRecord(
    val timestampMs: Long,
    val bpm: Int,
    val source: String,
)

class HeartRateHistory(context: Context) {
    private val database = HeartRateHistoryDatabase(context.applicationContext)

    fun record(timestampMs: Long, bpm: Int, source: String) {
        if (bpm !in 1..255) return
        database.writableDatabase.insert(TABLE, null, ContentValues().apply {
            put(COLUMN_TIMESTAMP, timestampMs)
            put(COLUMN_BPM, bpm)
            put(COLUMN_SOURCE, source)
        })
    }

    fun latest(limit: Int = 300): List<HeartRateRecord> {
        val records = mutableListOf<HeartRateRecord>()
        database.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_TIMESTAMP, COLUMN_BPM, COLUMN_SOURCE),
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP DESC, $COLUMN_ID DESC",
            limit.coerceIn(1, 1_000).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records += HeartRateRecord(cursor.getLong(0), cursor.getInt(1), cursor.getString(2))
            }
        }
        return records.asReversed()
    }

    fun exportCsv(output: OutputStream) {
        OutputStreamWriter(output, Charsets.UTF_8).buffered().use { writer ->
            writer.write("timestamp_utc,bpm,source\n")
            database.readableDatabase.query(
                TABLE,
                arrayOf(COLUMN_TIMESTAMP, COLUMN_BPM, COLUMN_SOURCE),
                null,
                null,
                null,
                null,
                "$COLUMN_TIMESTAMP ASC, $COLUMN_ID ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    writer.write(
                        csvRow(
                            HeartRateRecord(cursor.getLong(0), cursor.getInt(1), cursor.getString(2))
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TABLE = "heart_rate_samples"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp_ms"
        private const val COLUMN_BPM = "bpm"
        private const val COLUMN_SOURCE = "source"

        internal fun csvRow(record: HeartRateRecord): String =
            "${Instant.ofEpochMilli(record.timestampMs)},${record.bpm},${record.source}\n"
    }

    private class HeartRateHistoryDatabase(context: Context) :
        SQLiteOpenHelper(context, "heart_rate_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COLUMN_TIMESTAMP INTEGER NOT NULL, $COLUMN_BPM INTEGER NOT NULL, " +
                    "$COLUMN_SOURCE TEXT NOT NULL)"
            )
            db.execSQL("CREATE INDEX heart_rate_time_idx ON $TABLE ($COLUMN_TIMESTAMP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
