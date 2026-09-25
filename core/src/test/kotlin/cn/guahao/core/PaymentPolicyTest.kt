package cn.guahao.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PaymentPolicyTest {
    @Test fun lockedInsuranceInitializesWhileSevenUsesInsuranceEntry() {
        assertEquals(PaymentAction.INITIALIZE_INSURANCE,paymentAction(fixtureOrder(),PaymentPreference.INSURANCE_FIRST))
        assertEquals(PaymentAction.INSURANCE_PAYMENT,paymentAction(fixtureOrder().copy(phase=OrderPhase.INSURANCE_PENDING,rawStatus="7"),PaymentPreference.FULL_AMOUNT_BY_USER))
        assertEquals(PaymentAction.BOOKED,paymentAction(fixtureOrder().copy(phase=OrderPhase.INSURANCE_PAID,rawStatus="6",insuranceVerified=true),PaymentPreference.INSURANCE_FIRST))
        assertEquals(PaymentAction.NEEDS_ATTENTION,paymentAction(fixtureOrder().copy(phase=OrderPhase.INSURANCE_PAID,rawStatus="6"),PaymentPreference.INSURANCE_FIRST))
    }
    @Test fun lostInitializationResponseIsNeverRetriedAfterRestart() = runBlocking {
        val s=MemoryStore(TaskRecord(fixtureTask(),order=fixtureOrder())); val c=FakeClock()
        val g=FakeGateway().apply { locked=true; insuranceReply=InsuranceReply.UNKNOWN }
        PaymentCoordinator(g,s,c).prepare("task")
        PaymentCoordinator(g,s,c).prepare("task")
        assertEquals(1,g.initializations); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
        assertEquals(fixtureOrder().invalidAt,s.record.order?.invalidAt)
    }
    @Test fun insuranceStateAndOrderMustAgreeBeforeCompletion() = runBlocking {
        val s=MemoryStore(TaskRecord(fixtureTask(),order=fixtureOrder())); val g=FakeGateway().apply { locked=true }
        val p=PaymentCoordinator(g,s,FakeClock())
        g.paymentReply=InsuranceReply.PAID; p.refresh("task"); assertNotEquals(TaskPhase.BOOKED,s.record.phase)
        g.returned=listOf(fixtureOrder().copy(phase=OrderPhase.INSURANCE_PAID,rawStatus="6")); g.paymentReply=InsuranceReply.UNKNOWN
        p.refresh("task"); assertNotEquals(TaskPhase.BOOKED,s.record.phase)
        g.paymentReply=InsuranceReply.PAID; p.refresh("task"); assertEquals(TaskPhase.BOOKED,s.record.phase)
    }
    @Test fun unavailabilityOrSpecialConditionsNeverDowngrade() = runBlocking {
        val s=MemoryStore(TaskRecord(fixtureTask(),order=fixtureOrder())); val g=FakeGateway().apply { available=false; locked=true }
        PaymentCoordinator(g,s,FakeClock()).prepare("task")
        assertEquals(0,g.initializations); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
        assertEquals(PaymentAction.NEEDS_ATTENTION,paymentAction(fixtureOrder().copy(specialPaymentCondition=true),PaymentPreference.INSURANCE_FIRST))
    }
    @Test fun readinessRequiresEachPermission() {
        assertFalse(RuntimeReadiness(true,false,true,true,true).ready)
        assertFalse(RuntimeReadiness(true,true,false,true,true).ready)
        assertFalse(RuntimeReadiness(true,true,true,false,true).ready)
        assertFalse(RuntimeReadiness(true,true,true,true,false).ready)
    }
    @Test fun missingAsyncInsuranceContextDoesNotInitialize() = runBlocking {
        val s=MemoryStore(TaskRecord(fixtureTask(),order=fixtureOrder().copy(insuranceSupported=null)))
        val g=FakeGateway(); PaymentCoordinator(g,s,FakeClock()).prepare("task")
        assertEquals(0,g.initializations); assertEquals(TaskPhase.NEEDS_ATTENTION,s.record.phase)
    }
}
