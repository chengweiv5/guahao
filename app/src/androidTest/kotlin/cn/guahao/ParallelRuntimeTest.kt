package cn.guahao

import android.content.*
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import cn.guahao.hospital.*
import cn.guahao.runtime.BookingService
import cn.guahao.storage.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ParallelRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(): Context {
        val root = File(context.cacheDir, "parallel-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(root, "vault").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
        }
    }
    private fun task(id: String, patient: PatientRef = DemoGateway.patient) = BookingTask(id,
        VisitCondition(patient, DemoGateway.department, "demo-doctor", "林医生（虚构）", LocalDate.now().plusDays(100), 0, 1440, "2", 8000),
        Instant.now().minusSeconds(1), paymentPreference = PaymentPreference.FULL_AMOUNT_BY_USER)
    private fun attempt(t: BookingTask, scope: SubmissionScope? = null) = SubmissionAttempt("attempt-${t.id}", t.id,
        Candidate(t.condition.department, t.condition.doctorCode, t.condition.doctorName, t.condition.visitDate,
            "1", "09:00-09:30", 540, 570, 5000, 1, "2", false), t.releaseAt, setOf("baseline"), submissionScope = scope)

    @Test fun upgradeBackupMatchesEveryOriginalBusinessRecord() {
        val graph = context.graph
        val db = graph.store.readableDatabase
        val exists = db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'v1_migration_backup'", null).use { it.moveToFirst() }
        if (!exists) return // Fresh installation has no v1 state.
        var count = 0
        db.rawQuery("SELECT id,payload FROM v1_migration_backup", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val before = json.decodeFromString<TaskRecord>(graph.vault.decrypt(cursor.getBlob(1), "task:$id"))
                val after = graph.store.get(id)
                assertEquals(before.task, after.task.copy(binding = before.task.binding))
                assertEquals(before.attempt, after.attempt)
                assertEquals(before.order, after.order)
                assertEquals(before.insuranceStartedAt, after.insuranceStartedAt)
                assertEquals(before.manuallyResolved, after.manuallyResolved)
                count++
            }
        }
        assertTrue(count > 0)
    }

    @Test fun oneDeliveredAlarmStartsAllDueHospitals() {
        val graph = context.graph
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previous = settings.getBoolean("batteryAcknowledged", false)
        val used = graph.store.all().map { it.task.condition.visitDate }.toSet()
        val date = generateSequence(LocalDate.now().plusDays(150)) { it.plusDays(1) }.first { it !in used }
        val rows = listOf(DemoGateway.patient, DemoGateway.patientB).map { p ->
            task("parallel-alarm-test-${UUID.randomUUID()}", p).let { it.copy(condition = it.condition.copy(visitDate = date)) }
        }
        rows.forEach { graph.store.save(TaskRecord(it, TaskPhase.WAITING)) }
        try {
            settings.edit().putBoolean("batteryAcknowledged", true).commit()
            // Only one alarm is registered; receiver must drain both due rows.
            val lockScreen = InstrumentationRegistry.getArguments().getString("lockScreen") == "true"
            if (lockScreen) {
                androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).sleep()
                Thread.sleep(1000)
                assertFalse(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            }
            graph.scheduler.schedule(rows.first(), Instant.now().plusSeconds(2))
            val end = SystemClock.elapsedRealtime() + 15000
            while (rows.any { graph.store.get(it.id).order == null } && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            rows.forEach { assertEquals(TaskPhase.AWAITING_PAYMENT, graph.store.get(it.id).phase) }
            val notificationEnd = SystemClock.elapsedRealtime() + 12000
            while (manager.activeNotifications.count { n -> rows.any { it.id == n.tag } } < 2 &&
                SystemClock.elapsedRealtime() < notificationEnd) Thread.sleep(100)
            assertEquals(2, manager.activeNotifications.count { n -> rows.any { it.id == n.tag } })
            assertNotEquals(graph.store.get(rows[0].id).order!!.patient, graph.store.get(rows[1].id).order!!.patient)
        } finally {
            rows.forEach { graph.scheduler.cancel(it); graph.notifications.cancelResult(it.id) }
            settings.edit().putBoolean("batteryAcknowledged", previous).commit()
        }
    }

    @Test fun v1UpgradeKeepsRecordsAndUnknownIdentityBlocksOnlyItsPlatform() {
        val ctx = isolated(); val vault = EncryptedVault(ctx)
        val known = task("known"); val unknown = task("unknown", PatientRef("missing-legacy", "patient")).copy(demo = false)
        val rows = listOf(TaskRecord(known, TaskPhase.WAITING), TaskRecord(unknown, TaskPhase.NEEDS_ATTENTION, attempt(unknown)))
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("booking.db"), null).use { db ->
            db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY,generation INTEGER NOT NULL,phase TEXT NOT NULL,owner TEXT,payload BLOB NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX single_running_owner ON tasks ((1)) WHERE owner IS NOT NULL")
            rows.forEach { r -> db.insertOrThrow("tasks", null, ContentValues().apply {
                put("id", r.task.id); put("generation", r.task.generation); put("phase", r.phase.name)
                put("payload", vault.encrypt(Json.encodeToString(r), "task:${r.task.id}"))
            }) }
            db.version = 1
        }
        val db = BookingDatabase(ctx, vault)
        assertEquals(2, db.readableDatabase.version); assertEquals(2, db.all().size)
        rows.forEach { old -> assertEquals(old, db.get(old.task.id).let { it.copy(task = it.task.copy(binding = null)) }) }
        assertTrue(db.claim(known.id, 1, "one")); assertTrue(db.claim(unknown.id, 1, "two"))
        assertFalse(db.claim(known.id, 1, "duplicate"))
        val same = task("same", PatientRef("new-version", "patient")).copy(demo = false,
            binding = ConnectionBinding("other-hospital", "测试医院", "psc-youan", "合成", "account", "patient", "logical", "new-version", SubmissionScope("psc-youan", "account")))
        db.save(TaskRecord(same, TaskPhase.WAITING))
        assertFalse(db.beginSubmission(same.id, 1, attempt(same, same.binding!!.submissionScope), Instant.now()))
        val independent = task("independent", DemoGateway.patientB)
        db.save(TaskRecord(independent, TaskPhase.WAITING))
        assertTrue(db.beginSubmission(independent.id, 1, attempt(independent, demoBinding(DemoGateway.patientB).submissionScope), Instant.now()))
        db.close()
        val reopened = BookingDatabase(ctx, vault); reopened.recoverProcessOwnership()
        assertTrue(reopened.claim(known.id, 1, "recovered"))
        assertFalse(reopened.beginSubmission(same.id, 1, attempt(same, same.binding!!.submissionScope), Instant.now()))
        assertEquals(rows[1].attempt, reopened.get(unknown.id).attempt)
        reopened.close()
    }

    @Test fun scopesAreAtomicAndReimportDoesNotBypassUnknownAttempt() {
        val ctx = isolated(); val db = BookingDatabase(ctx, EncryptedVault(ctx))
        val a = task("a").copy(binding = demoBinding(DemoGateway.patient))
        val b = task("b").copy(binding = a.binding!!.copy(credentialVersionId = "another-version"))
        db.save(TaskRecord(a, TaskPhase.WAITING)); db.save(TaskRecord(b, TaskPhase.WAITING))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val gate = java.util.concurrent.CyclicBarrier(2)
            val results = listOf(a, b).map { t -> pool.submit<Boolean> {
                gate.await(); db.beginSubmission(t.id, 1, attempt(t, t.binding!!.submissionScope), Instant.now())
            } }.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it }); assertEquals(1, db.all().count { it.hasUnresolvedSubmission })
            val held = db.all().single { it.hasUnresolvedSubmission }
            db.update(held.task.id) { it.copy(phase = TaskPhase.NEEDS_ATTENTION) }
            db.recoverProcessOwnership()
            val waiting = db.all().single { !it.hasUnresolvedSubmission }
            assertFalse(db.beginSubmission(waiting.task.id, 1, attempt(waiting.task, waiting.task.binding!!.submissionScope), Instant.now()))
            db.update(held.task.id) { it.copy(manuallyResolved = true) }
            assertTrue(db.beginSubmission(waiting.task.id, 1, attempt(waiting.task, waiting.task.binding!!.submissionScope), Instant.now()))
        } finally { pool.shutdownNow(); db.close() }
    }

    @Test fun actualServiceAcceptsTwoIntentsAndStoppingOneLeavesOtherRunning() {
        val graph = context.graph
        // This test only runs when there are no currently executing user tasks.
        assertFalse(graph.store.all().any { it.phase in setOf(TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING) })
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previous = settings.getBoolean("batteryAcknowledged", false)
        val manager = context.getSystemService(NotificationManager::class.java)
        val used = graph.store.all().map { it.task.condition.visitDate }.toSet()
        val date = generateSequence(LocalDate.now().plusDays(100)) { it.plusDays(1) }.first { it !in used }
        val release = Instant.now().plusSeconds(5)
        val a = task("parallel-test-${UUID.randomUUID()}").copy(releaseAt = release)
        val b = task("parallel-test-${UUID.randomUUID()}", DemoGateway.patientB).copy(releaseAt = release,
            condition = task("condition", DemoGateway.patientB).condition.copy(visitDate = date))
        graph.store.save(TaskRecord(a, TaskPhase.WAITING)); graph.store.save(TaskRecord(b, TaskPhase.WAITING))
        try {
            settings.edit().putBoolean("batteryAcknowledged", true).commit()
            fun start(t: BookingTask) = context.startForegroundService(Intent(context, BookingService::class.java)
                .putExtra("taskId", t.id).putExtra("generation", t.generation))
            start(a); start(b); start(b)
            Thread.sleep(1000)
            assertTrue(manager.activeNotifications.any { it.id == 1 && it.notification.extras.getString("android.title") == "2 个挂号任务运行中" })
            graph.stop(a.id)
            val end = SystemClock.elapsedRealtime() + 15000
            while (graph.store.get(b.id).order == null && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            assertEquals(TaskPhase.STOPPED, graph.store.get(a.id).phase)
            assertNull(graph.store.get(a.id).attempt)
            assertEquals(TaskPhase.AWAITING_PAYMENT, graph.store.get(b.id).phase)
            assertEquals(DemoGateway.patientB, graph.store.get(b.id).order!!.patient)
            Thread.sleep(1000)
            assertTrue(manager.activeNotifications.any { it.tag == b.id && it.id == 3 })
            assertFalse(manager.activeNotifications.any { it.id == 1 })
        } finally {
            graph.stop(a.id)
            if (graph.store.get(b.id).order == null) graph.stop(b.id)
            settings.edit().putBoolean("batteryAcknowledged", previous).commit()
            graph.notifications.cancelResult(a.id); graph.notifications.cancelResult(b.id)
        }
    }
}
