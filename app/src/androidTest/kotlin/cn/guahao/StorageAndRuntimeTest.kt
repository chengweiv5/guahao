package cn.guahao

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import cn.guahao.hospital.DemoGateway
import cn.guahao.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageAndRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun keystoreCipherUsesUniqueIvAndBoundContext() {
        val vault=EncryptedVault(context)
        val a=vault.encrypt("private synthetic credential","a"); val b=vault.encrypt("private synthetic credential","a")
        assertFalse(a.contentEquals(b)); assertEquals("private synthetic credential",vault.decrypt(a,"a"))
        try { vault.decrypt(a,"b"); fail("AAD mismatch accepted") } catch (_: java.security.GeneralSecurityException) { }
        val name="test-${UUID.randomUUID()}"; vault.write(name,"synthetic roundtrip")
        assertEquals("synthetic roundtrip",EncryptedVault(context).read(name))
    }
    @Test fun databaseReopenRetainsAttemptAndClaimsAreExclusive() {
        val vault=EncryptedVault(context); val db=BookingDatabase(context,vault)
        val c=VisitCondition(DemoGateway.patient,DemoGateway.department,"demo-doctor","林医生（虚构）",LocalDate.now().plusDays(7),0,1440,"2",8000)
        val t=BookingTask(UUID.randomUUID().toString(),c,Instant.now())
        val candidate=Candidate(c.department,c.doctorCode,c.doctorName,c.visitDate,"1","09:00-09:30",540,570,5000,1,"2",false)
        db.save(TaskRecord(t,TaskPhase.SUBMITTING,SubmissionAttempt("attempt",t.id,candidate,Instant.now(),setOf("baseline"))))
        assertTrue(db.claim(t.id,t.generation,"first")); assertFalse(db.claim(t.id,t.generation,"second")); db.release(t.id,"first"); db.close()
        val reopened=BookingDatabase(context,vault)
        assertEquals("attempt",reopened.get(t.id).attempt?.id)
        assertEquals(setOf("baseline"),reopened.get(t.id).attempt?.baselineOrderNos)
        assertTrue(reopened.claim(t.id,t.generation,"second")); reopened.release(t.id,"second")
        // Synthetic test state remains explicit and terminal; no hospital calls.
        reopened.update(t.id) { it.copy(phase=TaskPhase.STOPPED,attempt=null,stopRequested=true,note="设备存储测试数据") }
        reopened.close()
    }
    @Test fun simulatedInsuranceFlowPersistsThroughCompletion() = runBlocking {
        val graph=context.graph
        val usedDates = graph.store.all().map { it.task.condition.visitDate }.toSet()
        val date = generateSequence(LocalDate.now().plusDays(100)) { it.plusDays(1) }.first { it !in usedDates }
        val condition=VisitCondition(DemoGateway.patient,DemoGateway.department,"demo-doctor","林医生（虚构）",date,0,1440,"2",8000)
        val task=BookingTask(UUID.randomUUID().toString(),condition,Instant.now().minusSeconds(1))
        graph.store.save(TaskRecord(task,TaskPhase.WAITING))
        graph.engine.run(task.id,1,"instrumentation")
        assertEquals(graph.store.get(task.id).note, TaskPhase.AWAITING_PAYMENT,graph.store.get(task.id).phase)
        graph.preparePayment(task.id)
        assertEquals(OrderPhase.INSURANCE_PENDING,graph.store.get(task.id).order?.phase)
        graph.store.update(task.id) { it.copy(order=it.order!!.copy(phase=OrderPhase.INSURANCE_PAID,rawStatus="6")) }
        graph.refreshPayment(task.id)
        assertEquals(TaskPhase.BOOKED,graph.store.get(task.id).phase)
        assertTrue(graph.store.get(task.id).order!!.insuranceVerified)
    }
}
