package cn.guahao.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlatformBookingTest {
    private val provider = RegistrationChannel.JINGTONG.id
    private val binding = testBinding(fixtureTask().condition.patient, provider).copy(hospitalId = "hospital")
    private val task = fixtureTask().copy(binding = binding)
    private val candidate = fixtureCandidate().copy(platform = PlatformProduct(provider, "hospital", "product", "time"))
    private fun order(number: String = "receipt") = fixtureOrder().copy(orderNo = number, source = provider, half = "",
        phase = OrderPhase.RESERVED_ONSITE, platform = PlatformOrder(provider, "hospital", 480, 510, "预约成功", null))

    @Test fun crossHospitalCandidatesCannotUsePlatformTaskAndExactOrderIdentityIsRequired() {
        assertTrue(eligible(task, candidate, task.releaseAt))
        for (c in listOf(candidate.copy(platform = null), candidate.copy(platform = candidate.platform!!.copy(hospitalId = "other"))))
            assertFalse(eligible(task, c, task.releaseAt))
        assertTrue(matches(order(), task, candidate))
        for (o in listOf(order().copy(platform = null), order().copy(platform = order().platform!!.copy(hospitalId = "other")),
            order().copy(doctorCode = null), order().copy(departmentCode = null), order().copy(feeFen = 5001),
            order().copy(platform = order().platform!!.copy(startMinute = 481)))) assertFalse(matches(o, task, candidate))
    }

    private fun gateway() = object : FakeGateway() {
        override fun binding(patient: PatientRef) = this@PlatformBookingTest.binding
        override suspend fun candidates(condition: VisitCondition) = listOf(candidate)
        override suspend fun querySubmission(patient: PatientRef): AsyncReply = error("Platform must use task receipt")
        override suspend fun querySubmission(task: BookingTask, attempt: SubmissionAttempt, patient: PatientRef): AsyncReply {
            assertEquals(task.id, attempt.taskId); assertEquals(candidate, attempt.candidate)
            return AsyncReply.OrderFound("receipt")
        }
    }

    @Test fun lostBinderReplyRecoversExactTaskReceiptAndOnsiteReservationWithoutAnotherSend() = runBlocking {
        val store = MemoryStore(TaskRecord(task, TaskPhase.WAITING))
        val gateway = gateway().apply { reply = LockReply.OutcomeUnknown; returned = listOf(order()) }
        val engine = BookingEngine(gateway, store, FakeClock())
        engine.run(task.id, task.generation, "worker")
        engine.run(task.id, task.generation, "restarted")
        assertEquals(1, gateway.locks)
        assertEquals("receipt", store.record.order?.orderNo)
        assertEquals(TaskPhase.BOOKED, store.record.phase)
        assertTrue(store.record.note.contains("到院"))
    }

    @Test fun sameConditionsManualOrderWithoutExactReceiptIsNeverClaimed() = runBlocking {
        val store = MemoryStore(TaskRecord(task, TaskPhase.WAITING))
        val gateway = gateway().apply { reply = LockReply.OutcomeUnknown; returned = listOf(order("manual-order")) }
        BookingEngine(gateway, store, FakeClock()).run(task.id, task.generation, "worker")
        assertEquals(1, gateway.locks); assertNull(store.record.order)
        assertEquals(TaskPhase.NEEDS_ATTENTION, store.record.phase)
    }

    @Test fun platformPaymentDoesNotInitializeAnotherHospitalsInsurance() = runBlocking {
        val pendingOrder = order().copy(phase = OrderPhase.LOCKED)
        val gateway = gateway().apply { locked = true; returned = listOf(pendingOrder) }
        val store = MemoryStore(TaskRecord(task, TaskPhase.AWAITING_PAYMENT, order = pendingOrder))
        PaymentCoordinator(gateway, store, FakeClock()).prepare(task.id)
        assertEquals(0, gateway.initializations)
        assertEquals(TaskPhase.AWAITING_PAYMENT, store.record.phase)
    }
}
