package cn.guahao.hospital.beijing

import kotlinx.serialization.json.*

/** Private identity stays in app memory/encrypted storage; toString never exposes it. */
class BeijingPatient internal constructor(
    val id: String, val displayName: String, internal val identityCard: String, val identityCardType: Int,
    val patientType: Int, val faceVerification: String?, val virtualPhone: Boolean,
    val cards: List<BeijingPatientCard>
) {
    override fun toString() = "BeijingPatient(redacted)"
}
class BeijingPatientCard internal constructor(
    internal val number: String, val displayNumber: String, val cardType: Int,
    val medicareType: Int, val label: String, val defaultCard: Boolean
) {
    override fun toString() = "BeijingPatientCard(redacted)"
}

internal object BeijingPatientParser {
    fun parse(data: JsonElement): List<BeijingPatient> {
        val root = data as? JsonObject ?: invalid()
        val patients = (root["patientList"] as? JsonArray ?: invalid()).map { raw ->
            val item = raw as? JsonObject ?: invalid()
            val cards = (item["cardList"] as? JsonArray ?: invalid()).map { rawCard ->
                val card = rawCard as? JsonObject ?: invalid()
                BeijingPatientCard(card.text("cardNo"), card.text("cardNoConfound"), card.int("cardType"),
                    card.int("medicareType"), card.text("medicareTypeView"), card.int("default_card") == 1)
            }
            if (cards.map { it.cardType to it.number }.distinct().size != cards.size) invalid()
            BeijingPatient(item.text("patientId"), item.text("patientNameConfound"), item.text("idCardNo"),
                item.int("idCardType"), item.int("patientType"), (item["faceVerifyResult"] as? JsonPrimitive)?.contentOrNull,
                (item["virtualPhone"] as? JsonPrimitive)?.booleanOrNull ?: invalid(), cards)
        }
        if (patients.map { it.id }.distinct().size != patients.size) invalid()
        return patients
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: invalid()
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: invalid()
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}
