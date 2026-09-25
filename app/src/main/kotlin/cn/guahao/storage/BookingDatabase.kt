package cn.guahao.storage

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.guahao.core.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class BookingDatabase(context: Context, private val vault: EncryptedVault) : SQLiteOpenHelper(context, "booking.db", null, 1), TaskStore {
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, generation INTEGER NOT NULL, phase TEXT NOT NULL, owner TEXT, payload BLOB NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX single_running_owner ON tasks ((1)) WHERE owner IS NOT NULL")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported migration") }
    /** Called once by Application, never by an Activity or a second service instance. App has one process. */
    @Synchronized fun recoverProcessOwnership() { writableDatabase.execSQL("UPDATE tasks SET owner = NULL") }
    private fun decode(id: String, payload: ByteArray) = json.decodeFromString<TaskRecord>(vault.decrypt(payload, "task:$id"))
    @Synchronized override fun get(id: String): TaskRecord = readableDatabase.rawQuery("SELECT payload FROM tasks WHERE id = ?", arrayOf(id)).use {
        check(it.moveToFirst()) { "任务不存在" }; decode(id, it.getBlob(0))
    }
    @Synchronized override fun all(): List<TaskRecord> = readableDatabase.rawQuery("SELECT id,payload FROM tasks ORDER BY rowid DESC", null).use {
        buildList { while (it.moveToNext()) add(decode(it.getString(0), it.getBlob(1))) }
    }
    @Synchronized override fun save(record: TaskRecord) {
        val values = ContentValues().apply {
            put("id", record.task.id); put("generation", record.task.generation); put("phase", record.phase.name)
            put("payload", vault.encrypt(json.encodeToString(record), "task:${record.task.id}"))
        }
        check(writableDatabase.insertOrThrow("tasks", null, values) != -1L)
    }
    @Synchronized override fun update(id: String, transform: (TaskRecord) -> TaskRecord): TaskRecord {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val next = transform(get(id))
            require(next.task.id == id)
            val values = ContentValues().apply {
                put("generation", next.task.generation); put("phase", next.phase.name)
                put("payload", vault.encrypt(json.encodeToString(next), "task:$id"))
            }
            check(db.update("tasks", values, "id = ?", arrayOf(id)) == 1)
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }
    @Synchronized override fun claim(id: String, generation: Long, owner: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.rawQuery("SELECT id FROM tasks WHERE owner IS NOT NULL", null).use { it.moveToFirst() }) return false
            val record = get(id)
            if (record.task.generation != generation) return false
            val count = db.update("tasks", ContentValues().apply { put("owner", owner) }, "id = ? AND owner IS NULL", arrayOf(id))
            db.setTransactionSuccessful(); return count == 1
        } finally { db.endTransaction() }
    }
    @Synchronized override fun release(id: String, owner: String) {
        writableDatabase.update("tasks", ContentValues().apply { putNull("owner") }, "id = ? AND owner = ?", arrayOf(id, owner))
    }
}
