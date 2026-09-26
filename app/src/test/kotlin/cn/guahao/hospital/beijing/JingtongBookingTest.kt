package cn.guahao.hospital.beijing

import cn.guahao.core.*
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

internal class BeijingTestSecrets : SecretStore {
    val values = mutableMapOf<String, String>()
    override fun read(name: String) = values[name]
    override fun write(name: String, text: String) { values[name] = text }
}
internal object JtFixture {
    val channel = RegistrationChannel.JINGTONG
    val card = BeijingPatientCard("synthetic-card", "****1234", 1, 3, "自费", true)
    val patient = BeijingPatient("synthetic-patient", "测*", "synthetic-document", 1, 0, "wait_verify", false, listOf(card))
    val selection = BeijingPatientSelection("synthetic-selection", channel, "hospital-a", "测试医院", null, "synthetic-account", patient, card)
    val account = BeijingAccountSnapshot(channel, "synthetic-account", listOf(patient), true)
    val binding = ConnectionBinding(selection.hospitalId, selection.hospitalName, channel.id, channel.title,
        "account-index", "patient-index", "connection-index", selection.reference.sessionId, SubmissionScope(channel.id, "account-index"))
    val date = LocalDate.of(2026, 10, 8)
    val time = Instant.parse("2026-10-01T00:00:00Z")
    val department = DepartmentRef("parent", "child", "内科", "测试科室", "")
    val task = BookingTask("synthetic-task", VisitCondition(selection.reference, department, "doctor", "测试医生", date, 0, 1440, "2", 8000),
        time, demo = false, binding = binding)
    val candidate = Candidate(department, "doctor", "测试医生", date, "1", "08:00-08:30", 480, 510, 5000, 1, "", false,
        PlatformProduct(channel.id, selection.hospitalId, "synthetic-product", "synthetic-time"))
    fun reply(data: JsonElement) = BeijingQueryReply(200, "application/json", buildJsonObject { put("code", "0000"); put("data", data) }.toString())
}

class JingtongBookingTest {
    private class Harness(val vault: BeijingTestSecrets = BeijingTestSecrets()) {
        var sent = 0; var confirmed = 0; var stopped = false
        var responseLost = false; var cancel = false; var failWrite = false
        var fee = "50.00"; var advice: String? = null; var now = JtFixture.time
        val engine = BeijingSubmission(BeijingQueryTransport { JtFixture.reply(JsonPrimitive(false)) }, BeijingMutationTransport { request ->
            BeijingMutationPolicy.validate(request)
            if (request.path == "product/confirmV2") {
                confirmed++
                JtFixture.reply(buildJsonObject { put("totalFee", fee); put("doctorName", "测试医生"); put("regHalf", "1"); advice?.let { put("takeTimeTips", it) } })
            } else {
                assertTrue(vault.values.values.any { it.contains("\"sent\":true") })
                assertEquals(3, request.body!!["cardType"]!!.jsonPrimitive.int)
                assertEquals("synthetic-product", request.body["uniqProductKey"]!!.jsonPrimitive.content)
                assertEquals("synthetic-time", request.body["uniqueProductTimeKey"]!!.jsonPrimitive.content)
                sent++
                if (responseLost) throw BeijingQueryException(BeijingFailureKind.NETWORK)
                if (cancel) throw CancellationException()
                JtFixture.reply(buildJsonObject { put("patientId", JtFixture.patient.id); put("orderId", "synthetic-order") })
            }
        }, object : SecretStore {
            override fun read(name: String) = vault.read(name)
            override fun write(name: String, text: String) { if (failWrite) error("disk full"); vault.write(name, text) }
        }) { now }
        suspend fun submit(task: BookingTask = JtFixture.task, candidate: Candidate = JtFixture.candidate) =
            engine.submit(JtFixture.selection, JtFixture.account, task, candidate) { !stopped }
    }

    @Test fun durableReceiptRecoversWithoutSendingAndRejectsChangedPayloadOrGeneration() = runBlocking {
        val first = Harness()
        assertEquals(LockReply.OrderCreated("synthetic-order"), first.submit())
        val restarted = Harness(first.vault)
        assertEquals(LockReply.OrderCreated("synthetic-order"), restarted.submit())
        assertEquals(AsyncReply.OrderFound("synthetic-order"), restarted.engine.receipt(JtFixture.task, JtFixture.candidate))
        assertTrue(restarted.engine.receipt(JtFixture.task.copy(generation = 2), JtFixture.candidate) is AsyncReply.Unknown)
        assertTrue(restarted.engine.receipt(JtFixture.task, JtFixture.candidate.copy(feeFen = 4000)) is AsyncReply.Unknown)
        assertEquals(LockReply.OutcomeUnknown, restarted.submit(candidate = JtFixture.candidate.copy(feeFen = 4000)))
        assertEquals(0, restarted.sent); assertEquals(0, restarted.confirmed)
    }

    @Test fun lostResponseOrCancellationNeverRepeatsAnUncertainSend() = runBlocking {
        for (cancellation in listOf(false, true)) {
            val h = Harness().apply { responseLost = !cancellation; cancel = cancellation }
            try { assertEquals(LockReply.OutcomeUnknown, h.submit()) } catch (e: CancellationException) { assertTrue(cancellation) }
            val restarted = Harness(h.vault)
            assertEquals(LockReply.OutcomeUnknown, restarted.submit())
            assertTrue(restarted.engine.receipt(JtFixture.task, JtFixture.candidate) is AsyncReply.Unknown)
            assertEquals(1, h.sent); assertEquals(0, restarted.sent)
        }
    }

    @Test fun diskFailureStopsBeforeSend() = runBlocking {
        val h = Harness().apply { failWrite = true }
        try { h.submit(); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, h.sent)
    }

    @Test fun stopFeeChangeAndHospitalAdvicePreventSubmission() = runBlocking {
        for (h in listOf(Harness().apply { stopped = true }, Harness().apply { fee = "80.01" }, Harness().apply { advice = "请提前到院核验" })) {
            try { h.submit(); fail() } catch (_: HospitalException) { }
            assertEquals(0, h.sent)
            assertTrue(h.vault.values.isEmpty())
        }
    }

    @Test fun stopOrDeadlineAfterPreparationStillPreventsSubmission() = runBlocking {
        for (expired in listOf(false, true)) {
            val h = Harness(); var checks = 0
            try {
                h.engine.submit(JtFixture.selection, JtFixture.account, JtFixture.task, JtFixture.candidate) {
                    checks++
                    if (checks == 3 && expired) h.now = JtFixture.task.deadline
                    checks < 3 || expired
                }; fail()
            } catch (_: HospitalException) { }
            assertEquals(0, h.sent)
        }
    }

    @Test fun channelOrHospitalMismatchAndUnverifiedAccountCannotSubmit() = runBlocking {
        val h = Harness()
        try { h.submit(candidate = JtFixture.candidate.copy(platform = JtFixture.candidate.platform!!.copy(hospitalId = "other"))); fail() }
        catch (_: IllegalArgumentException) { }
        try { h.engine.submit(JtFixture.selection, BeijingAccountSnapshot(JtFixture.channel, "synthetic-account", listOf(JtFixture.patient)),
            JtFixture.task, JtFixture.candidate) { true }; fail() } catch (_: HospitalException) { }
        assertEquals(0, h.sent); assertEquals(0, h.confirmed)
        assertFalse(JingtongCapabilities.automaticBookingVerified)
    }
}
