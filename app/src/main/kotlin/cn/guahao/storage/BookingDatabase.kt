package cn.guahao.storage

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cn.guahao.core.*
import cn.guahao.hospital.HospitalIdentity
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class BookingDatabase(context: Context, private val vault: EncryptedVault) : SQLiteOpenHelper(context, "booking.db", null, 2), TaskStore {
    private val identities = HospitalIdentity(vault)
    private val json = Json { ignoreUnknownKeys = true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, generation INTEGER NOT NULL, phase TEXT NOT NULL, owner TEXT, payload BLOB NOT NULL)")
        createScopeSchema(db)
    }
    private fun createScopeSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN hospital_id TEXT")
        db.execSQL("ALTER TABLE tasks ADD COLUMN provider_id TEXT")
        db.execSQL("CREATE TABLE submission_scopes (task_id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, account_key TEXT)")
        db.execSQL("CREATE INDEX scope_lookup ON submission_scopes(provider_id, account_key)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2)
        // SQLiteOpenHelper encloses this entire migration in one transaction.
        db.execSQL("CREATE TABLE v1_migration_backup AS SELECT * FROM tasks")
        db.execSQL("DROP INDEX single_running_owner")
        createScopeSchema(db)
        val records = db.rawQuery("SELECT id,payload FROM tasks", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0), cursor.getBlob(1))) }
        }
        for (old in records) {
            val binding = old.task.binding ?: identities.resolve(old.task.condition.patient)
            val next = old.copy(task = old.task.copy(binding = binding))
            db.update("tasks", values(next), "id = ?", arrayOf(old.task.id))
            syncScope(db, next)
        }
    }
    private fun values(record: TaskRecord) = ContentValues().apply {
        put("generation", record.task.generation); put("phase", record.phase.name)
        put("hospital_id", record.task.binding?.hospitalId)
        put("provider_id", record.task.binding?.providerId)
        put("payload", vault.encrypt(json.encodeToString(record), "task:${record.task.id}"))
    }
    private fun syncScope(db: SQLiteDatabase, record: TaskRecord) {
        db.delete("submission_scopes", "task_id = ?", arrayOf(record.task.id))
        if (!record.hasUnresolvedSubmission) return
        // Missing legacy identity blocks this platform, not connecting or independent providers.
        val scope = record.attempt?.submissionScope ?: record.task.binding?.submissionScope
            ?: identities.resolve(record.task.condition.patient)?.submissionScope
            ?: SubmissionScope(if (record.task.demo) "demo-a" else "psc-youan", null)
        db.insertOrThrow("submission_scopes", null, ContentValues().apply {
            put("task_id", record.task.id); put("provider_id", scope.providerId); put("account_key", scope.accountKey)
        })
    }
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
        val db = writableDatabase
        db.beginTransaction()
        try {
            val next = record.copy(task = record.task.copy(binding = record.task.binding ?: identities.resolve(record.task.condition.patient)))
            check(db.insertOrThrow("tasks", null, values(next).apply { put("id", next.task.id) }) != -1L)
            syncScope(db, next)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized override fun update(id: String, transform: (TaskRecord) -> TaskRecord): TaskRecord {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val next = transform(get(id))
            require(next.task.id == id)
            check(db.update("tasks", values(next), "id = ?", arrayOf(id)) == 1)
            syncScope(db, next)
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }
    @Synchronized override fun claim(id: String, generation: Long, owner: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = get(id)
            if (record.task.generation != generation) return false
            val count = db.update("tasks", ContentValues().apply { put("owner", owner) }, "id = ? AND owner IS NULL", arrayOf(id))
            db.setTransactionSuccessful(); return count == 1
        } finally { db.endTransaction() }
    }
    @Synchronized override fun release(id: String, owner: String) {
        writableDatabase.update("tasks", ContentValues().apply { putNull("owner") }, "id = ? AND owner = ?", arrayOf(id, owner))
    }
    @Synchronized override fun beginSubmission(id: String, generation: Long, attempt: SubmissionAttempt, now: Instant): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = get(id)
            if (r.task.generation != generation || r.stopRequested || r.attempt != null || r.order != null ||
                r.manuallyResolved || !now.isBefore(r.task.deadline) || now.isBefore(r.task.releaseAt)) return false
            val scope = attempt.submissionScope ?: return false
            require(attempt.taskId == id && r.task.binding?.submissionScope == scope)
            val conflict = db.rawQuery("SELECT task_id FROM submission_scopes WHERE provider_id = ? AND (account_key IS NULL OR ? IS NULL OR account_key = ?)",
                arrayOf(scope.providerId, scope.accountKey, scope.accountKey)).use { it.moveToFirst() }
            if (conflict) return false
            update(id) { it.copy(attempt = attempt, phase = TaskPhase.SUBMITTING, note = "正在提交，请等待医院结果") }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

}
