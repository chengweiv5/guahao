package cn.guahao

import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import cn.guahao.hospital.beijing.*
import cn.guahao.storage.EncryptedVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.*
import java.util.UUID

/** Reads only a synthetic local receipt over real private Binder IPC, without loading a website. */
class JingtongReceiptIpcTest {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun receiptSurvivesClientRecreationWithoutVisibleBrowserOrNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val channel = RegistrationChannel.JINGTONG
        val card = BeijingPatientCard("synthetic", "****1234", 1, 3, "自费", true)
        val patient = BeijingPatient("synthetic", "测*", "synthetic", 1, 0, "wait_verify", false, listOf(card))
        val selection = BeijingPatientSelection(UUID.randomUUID().toString(), channel, "synthetic", "测试医院", null, "synthetic", patient, card)
        val department = DepartmentRef("parent", "child", "测试大科室", "测试科室", "")
        val task = BookingTask(UUID.randomUUID().toString(), VisitCondition(selection.reference, department, "doctor", "测试医生", LocalDate.now(), 0, 1440, "2", 8000),
            Instant.now(), demo = false)
        val candidate = Candidate(department, "doctor", "测试医生", task.condition.visitDate, "1", "08:00-08:30", 480, 510, 5000, 1, "", false,
            PlatformProduct(channel.id, "synthetic", "product", "time"))
        val fingerprint = hash(Json.encodeToString(task) + "\n" + Json.encodeToString(candidate))
        val vault = EncryptedVault(context)
        vault.write("beijing-send-" + hash("${task.id}:${task.generation}"), buildJsonObject {
            put("sent", true); put("fingerprint", fingerprint); put("orderId", "synthetic-receipt")
        }.toString())
        assertEquals(AsyncReply.OrderFound("synthetic-receipt"), BeijingBrowserClient(context).receipt(selection, task, candidate))
        assertEquals(AsyncReply.OrderFound("synthetic-receipt"), BeijingBrowserClient(context).receipt(selection, task, candidate))
        assertTrue(BeijingBrowserClient(context).receipt(selection, task, candidate.copy(feeFen = 4000)) is AsyncReply.Unknown)
        assertTrue(BeijingBrowserClient(context).receipt(selection, task.copy(id = UUID.randomUUID().toString()), candidate) is AsyncReply.Unknown)
    }
}
