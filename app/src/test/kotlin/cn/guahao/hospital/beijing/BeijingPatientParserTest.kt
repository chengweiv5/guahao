package cn.guahao.hospital.beijing

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeijingPatientParserTest {
    private val fixture = """{"patientList":[{"patientId":"synthetic-id","patientNameConfound":"测*","idCardNo":"synthetic-document","idCardType":1,"patientType":0,"faceVerifyResult":"wait_verify","virtualPhone":false,"cardList":[{"cardNo":"synthetic-card","cardNoConfound":"****1234","cardType":1,"medicareType":3,"medicareTypeView":"自费","default_card":1}]}],"hisPatientList":[]}"""
    private fun parse(raw: String = fixture) = BeijingPatientParser.parse(Json.parseToJsonElement(raw))
    @Test fun maskedDisplayAndCardSubmissionTypeStaySeparate() {
        val patient = parse().single()
        assertEquals("测*", patient.displayName)
        assertEquals("wait_verify", patient.faceVerification)
        val card = patient.cards.single()
        assertEquals(1, card.cardType)
        assertEquals(3, card.medicareType)
        assertEquals("****1234", card.displayNumber)
        assertFalse(patient.toString().contains("synthetic"))
        assertFalse(card.toString().contains("synthetic"))
    }
    @Test fun duplicatePatientIdsCannotProduceAmbiguousConnection() {
        val patient = Json.parseToJsonElement(fixture).jsonObject["patientList"]!!.jsonArray.single()
        try { parse(buildJsonObject { put("patientList", JsonArray(listOf(patient, patient))) }.toString()); fail() }
        catch (e: BeijingQueryException) { assertEquals(BeijingFailureKind.INVALID_RESPONSE, e.kind) }
    }
    @Test fun missingIdentityOrCardsFailsClosedButEmptyAuthenticatedListIsValid() {
        for (raw in listOf(fixture.replace("synthetic-id", ""), fixture.replace("\"virtualPhone\":false", "\"virtualPhone\":null"), """{"patientList":null}""")) {
            try { parse(raw); fail() }
            catch (e: BeijingQueryException) { assertEquals(BeijingFailureKind.INVALID_RESPONSE, e.kind) }
        }
        assertTrue(parse("""{"patientList":[]}""").isEmpty())
    }
}
