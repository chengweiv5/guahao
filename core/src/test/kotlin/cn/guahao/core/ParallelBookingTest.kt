package cn.guahao.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

private class ParallelStore(records: List<TaskRecord>) : TaskStore {
    private val rows = records.associateBy { it.task.id }.toMutableMap()
    private val owners = mutableMapOf<String, String>()
    @Synchronized override fun get(id: String) = rows.getValue(id)
    @Synchronized override fun all() = rows.values.toList()
    @Synchronized override fun save(record: TaskRecord) { rows[record.task.id] = record }
    @Synchronized override fun update(id: String, transform: (TaskRecord) -> TaskRecord) = transform(get(id)).also { rows[id] = it }
    @Synchronized override fun claim(id: String, generation: Long, owner: String): Boolean {
        if (id in owners || get(id).task.generation != generation) return false
        owners[id] = owner; return true
    }
    @Synchronized override fun release(id: String, owner: String) { if (owners[id] == owner) owners.remove(id) }
    @Synchronized override fun beginSubmission(id: String, generation: Long, attempt: SubmissionAttempt, now: Instant): Boolean {
        val r = get(id)
        if (r.attempt != null || r.stopRequested || r.task.generation != generation || !now.isBefore(r.task.deadline)) return false
        if (all().any { it.hasUnresolvedSubmission && it.attempt!!.submissionScope!!.conflicts(attempt.submissionScope!!) }) return false
        update(id) { it.copy(attempt = attempt, phase = TaskPhase.SUBMITTING) }; return true
    }
}

class ParallelBookingTest {
    private fun task(id: String, provider: String = id) = fixtureTask().let {
        val patient = PatientRef("version-$id", "patient-$id")
        it.copy(id = id, condition = it.condition.copy(patient = patient), binding = testBinding(patient, provider))
    }
    @Test fun independentHospitalsEnterLockTogetherAndDuplicateWakeupDoesNotSubmit() = runBlocking {
        val tasks = listOf(task("a"), task("b")); val store = ParallelStore(tasks.map { TaskRecord(it, TaskPhase.WAITING) })
        val bothEntered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val locked = mutableSetOf<String>()
        val g = object : FakeGateway() {
            override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply {
                synchronized(locked) { locked.add(task.id); if (locked.size == 2) bothEntered.complete(Unit) }
                release.await(); return LockReply.Accepted
            }
            override suspend fun querySubmission(patient: PatientRef) = AsyncReply.OrderFound("order-${patient.sessionId}")
            override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate) =
                if (release.isCompleted) listOf(fixtureOrder().copy(patient = patient, orderNo = "order-${patient.sessionId}")) else emptyList()
        }
        val engine = BookingEngine(g, store, FakeClock())
        val a = launch { engine.run("a", 1, "a") }; val b = launch { engine.run("b", 1, "b") }
        withTimeout(3000) { bothEntered.await() }
        engine.run("a", 1, "duplicate")
        assertEquals(setOf("a", "b"), locked)
        release.complete(Unit); joinAll(a, b)
        assertTrue(store.all().all { it.phase == TaskPhase.AWAITING_PAYMENT })
        assertNotEquals(store.get("a").order!!.orderNo, store.get("b").order!!.orderNo)
    }
    @Test fun unknownScopeSurvivesNewCredentialVersionWhileIndependentProviderFinishes() = runBlocking {
        val a = task("a", "shared"); val b = task("b", "shared"); val c = task("c", "independent")
        val attempt = SubmissionAttempt("a-attempt", "a", fixtureCandidate(), a.releaseAt.minusSeconds(200), emptySet(), submissionScope = a.binding!!.submissionScope)
        val store = ParallelStore(listOf(TaskRecord(a, TaskPhase.NEEDS_ATTENTION, attempt), TaskRecord(b, TaskPhase.WAITING), TaskRecord(c, TaskPhase.WAITING)))
        val blocked = FakeGateway()
        BookingEngine(blocked, store, FakeClock(b.deadline.minusSeconds(2))).run("b", 1, "b")
        assertEquals(0, blocked.locks); assertEquals(TaskPhase.EXPIRED, store.get("b").phase)
        assertEquals(attempt, store.get("a").attempt)
        val independent = FakeGateway().apply { returned = listOf(fixtureOrder().copy(patient = c.condition.patient)) }
        BookingEngine(independent, store, FakeClock()).run("c", 1, "c")
        assertEquals(1, independent.locks); assertNotNull(store.get("c").order)
    }
    @Test fun stoppingOneWaitingHospitalDoesNotStopAnother() = runBlocking {
        val a = task("a"); val b = task("b")
        val store = ParallelStore(listOf(TaskRecord(a, TaskPhase.WAITING), TaskRecord(b, TaskPhase.WAITING)))
        val gateway = FakeGateway().apply { returned = listOf(fixtureOrder().copy(patient = b.condition.patient)) }
        val engine = BookingEngine(gateway, store, FakeClock())
        engine.stop("a")
        engine.run("a", 1, "a"); engine.run("b", 1, "b")
        assertEquals(TaskPhase.STOPPED, store.get("a").phase)
        assertEquals(TaskPhase.AWAITING_PAYMENT, store.get("b").phase)
        assertEquals(1, gateway.locks)
    }
    @Test fun readonlyRecoveryNeverInitializesPaymentOrLocks() = runBlocking {
        val t = task("a"); val original = t.condition.patient; val fresh = PatientRef("new-version", original.patientId)
        val store = ParallelStore(listOf(TaskRecord(t, TaskPhase.AWAITING_PAYMENT, order = fixtureOrder().copy(patient = original), reconciliationPatient = fresh)))
        val g = object : FakeGateway() {
            override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate): List<OrderSnapshot> {
                assertEquals(fresh, patient)
                return listOf(fixtureOrder().copy(patient = fresh, phase = OrderPhase.INSURANCE_PENDING))
            }
            override suspend fun paymentState(patient: PatientRef, orderNo: String): InsuranceReply { assertEquals(fresh, patient); return InsuranceReply.PENDING }
        }
        PaymentCoordinator(g, store, FakeClock()).prepare("a")
        assertEquals(0, g.initializations); assertEquals(0, g.locks)
        assertEquals(original, store.get("a").order!!.patient)
    }
}
