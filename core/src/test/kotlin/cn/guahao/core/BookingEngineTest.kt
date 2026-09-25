package cn.guahao.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MemoryStore(record: TaskRecord) : TaskStore {
    var record = record
    var owner: String? = null
    var afterUpdate: ((TaskRecord)->Unit)? = null
    @Synchronized override fun get(id: String) = record
    @Synchronized override fun all() = listOf(record)
    @Synchronized override fun save(record: TaskRecord) { this.record = record }
    @Synchronized override fun update(id: String, transform: (TaskRecord)->TaskRecord): TaskRecord {
        record = transform(record); afterUpdate?.invoke(record); return record
    }
    @Synchronized override fun claim(id: String, generation: Long, owner: String): Boolean {
        if (this.owner != null || record.task.generation != generation) return false
        this.owner=owner; return true
    }
    @Synchronized override fun release(id: String, owner: String) { if (this.owner==owner) this.owner=null }
}
class FakeClock(var instant: Instant = fixtureTask().releaseAt) : BookingClock {
    var waits = mutableListOf<Long>()
    override fun now() = instant
    override suspend fun delayMillis(value: Long) { waits += value; instant = instant.plusMillis(value); yield() }
}
fun fixtureOrder() = OrderSnapshot("test-order", fixtureTask().condition.patient, "doctor", "eye", "示例医生", "眼科", "2",
    fixtureTask().condition.visitDate, "1", "08:00-08:30", 5000, OrderPhase.LOCKED, "1", fixtureTask().releaseAt.plusSeconds(1800), false, insuranceSupported=true)
open class FakeGateway : BookingGateway {
    var locks = 0; var initializations = 0; var locked = false
    var reply: LockReply = LockReply.Accepted
    var result: AsyncReply = AsyncReply.OrderFound("test-order",PaymentContext(false,true,false))
    var existing = emptyList<OrderSnapshot>()
    var returned = listOf(fixtureOrder())
    var onLock: (() -> Unit)? = null
    var insuranceReply = InsuranceReply.ACCEPTED
    var paymentReply = InsuranceReply.PENDING
    var available = true
    override suspend fun departments(patient: PatientRef) = listOf(fixtureTask().condition.department)
    override suspend fun candidates(condition: VisitCondition) = listOf(fixtureCandidate())
    override suspend fun validateBookingAccess(patient: PatientRef) = true
    override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate) = if (locked) returned else existing
    override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply { locks++; locked=true; onLock?.invoke(); return reply }
    override suspend fun querySubmission(patient: PatientRef) = result
    override suspend fun insuranceAvailable(patient: PatientRef) = available
    override suspend fun initializeInsurance(patient: PatientRef, orderNo: String): InsuranceReply { initializations++; return insuranceReply }
    override suspend fun paymentState(patient: PatientRef, orderNo: String) = paymentReply
}
class BookingEngineTest {
    private fun store() = MemoryStore(TaskRecord(fixtureTask(), TaskPhase.WAITING))
    @Test fun locksOnceAndPersistsBeforeSending() = runBlocking {
        val s=store(); val g=FakeGateway(); val clock=FakeClock()
        g.onLock = { assertNotNull(s.record.attempt); assertEquals(TaskPhase.SUBMITTING,s.record.phase) }
        val e=BookingEngine(g,s,clock); e.run("task",1,"a"); e.run("task",1,"a")
        assertEquals(1,g.locks); assertEquals(TaskPhase.AWAITING_PAYMENT,s.record.phase)
    }
    @Test fun durableAttemptSurvivesRestartWithoutLocking() = runBlocking {
        val s=store(); val a=SubmissionAttempt("attempt","task",fixtureCandidate(),fixtureTask().releaseAt, emptySet())
        s.record=s.record.copy(attempt=a,phase=TaskPhase.SUBMITTING)
        val g=FakeGateway().apply { locked=true }
        BookingEngine(g,s,FakeClock()).run("task",1,"b")
        assertEquals(0,g.locks); assertEquals("test-order",s.record.order?.orderNo)
    }
    @Test fun lostResponseAndEmptyListsNeverCauseRetry() = runBlocking {
        val s=store(); val g=FakeGateway().apply { reply=LockReply.OutcomeUnknown; returned=emptyList(); result=AsyncReply.Pending }; val c=FakeClock()
        BookingEngine(g,s,c).run("task",1,"a")
        assertEquals(1,g.locks); assertNotNull(s.record.attempt); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
        assertEquals(fixtureTask().releaseAt.plusSeconds(120),c.now())
        BookingEngine(g,s,c).run("task",1,"b"); assertEquals(1,g.locks)
    }
    @Test fun unrelatedOrConflictingOrdersAreNotClaimed() = runBlocking {
        for (bad in listOf(fixtureOrder().copy(doctorCode="other"),fixtureOrder().copy(patient=PatientRef("test","other")),fixtureOrder().copy(orderNo="another"))) {
            val s=store(); val g=FakeGateway().apply { returned=listOf(bad) }
            BookingEngine(g,s,FakeClock()).run("task",1,"a")
            assertNull(s.record.order); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase); assertEquals(1,g.locks)
        }
    }
    @Test fun preexistingOrderPreventsAnotherLock() = runBlocking {
        val s=store(); val g=FakeGateway().apply { existing=listOf(fixtureOrder()) }
        BookingEngine(g,s,FakeClock()).run("task",1,"a")
        assertEquals(0,g.locks); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
    }
    @Test fun deadlineAndOldGenerationDoNotSubmit() = runBlocking {
        for ((generation,now) in listOf(0L to fixtureTask().releaseAt, 1L to fixtureTask().deadline)) {
            val s=store(); val g=FakeGateway(); BookingEngine(g,s,FakeClock(now)).run("task",generation,"a"); assertEquals(0,g.locks)
        }
    }
    @Test fun successfulInFlightReplyAfterDeadlineIsSaved() = runBlocking {
        val s=store(); val g=FakeGateway(); val c=FakeClock(fixtureTask().deadline.minusSeconds(1))
        g.onLock={ c.instant=c.instant.plusSeconds(2) }
        BookingEngine(g,s,c).run("task",1,"a")
        assertNotNull(s.record.order); assertEquals(1,g.locks)
    }
    @Test fun stopAfterDurableRecordDoesNotSend() = runBlocking {
        val s=store(); val g=FakeGateway(); val e=BookingEngine(g,s,FakeClock())
        s.afterUpdate={ if (it.phase==TaskPhase.SUBMITTING) { s.afterUpdate=null; e.stop("task") } }
        e.run("task",1,"a"); assertEquals(0,g.locks); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
    }
    @Test fun concurrentWakeupsHaveOneOwner() = runBlocking {
        val s=store(); val g=FakeGateway(); val c=FakeClock()
        coroutineScope { repeat(8) { launch { BookingEngine(g,s,c).run("task",1,"owner-$it") } } }
        assertEquals(1,g.locks)
    }
    @Test fun noStockCanRetryOnlyInsideOriginalWindow() = runBlocking {
        val s=store(); val g=FakeGateway().apply { reply=LockReply.NoStock; returned=emptyList() }; val c=FakeClock(fixtureTask().deadline.minusSeconds(2))
        BookingEngine(g,s,c).run("task",1,"a")
        assertEquals(1,g.locks); assertNull(s.record.attempt); assertEquals(TaskPhase.EXPIRED,s.record.phase)
    }
    @Test fun backoffHonorsHospitalRetryAfter() = runBlocking {
        val s=store(); val g=object:FakeGateway() { override suspend fun candidates(condition: VisitCondition): List<Candidate> { throw HospitalException("busy",60000,true) } }
        val c=FakeClock(fixtureTask().deadline.minusSeconds(30)); BookingEngine(g,s,c).run("task",1,"a")
        assertEquals(listOf(60000L),c.waits); assertEquals(0,g.locks)
    }
    @Test fun preexistingDifferentSlotInAcceptedWindowAlsoBlocks() = runBlocking {
        val s=store(); val g=FakeGateway().apply { existing=listOf(fixtureOrder().copy(hour="09:00-09:30")) }
        BookingEngine(g,s,FakeClock()).run("task",1,"a")
        assertEquals(0,g.locks); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
    }
    @Test fun recoveredClockRollbackRequiresAttention() = runBlocking {
        val s=store(); s.record=s.record.copy(lastEventAt=fixtureTask().releaseAt.plusSeconds(120))
        val g=FakeGateway(); BookingEngine(g,s,FakeClock()).run("task",1,"a")
        assertEquals(0,g.locks); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
    }
}
