package cn.guahao.hospital.beijing

import cn.guahao.core.*
import cn.guahao.hospital.HospitalIdentity
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** This records a user selection, not permission to book or proof of a background-ready session. */
@Serializable class BeijingPatientSelection internal constructor(
    val id: String,
    val channel: RegistrationChannel,
    val hospitalId: String,
    val hospitalName: String,
    val campusId: String?,
    internal val accountId: String,
    val patient: BeijingPatient,
    val card: BeijingPatientCard
) {
    override fun toString() = "BeijingPatientSelection(redacted)"
    val reference get() = PatientRef("beijing-$id", "selected-patient")
}

class BeijingConnectionRepository(private val vault: SecretStore, private val source: BeijingAccountSource,
    private val now: () -> Instant = Instant::now) {
    private class Checked(val account: BeijingAccountSnapshot, val at: Instant)
    private val checks = mutableMapOf<RegistrationChannel, Checked>()
    private val revisions = mutableMapOf<RegistrationChannel, Long>()
    private val gates = RegistrationChannel.entries.associateWith { Mutex() }
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun invalidate(channel: RegistrationChannel) {
        checks.remove(channel)
        revisions[channel] = (revisions[channel] ?: 0L) + 1
    }

    suspend fun refresh(channel: RegistrationChannel): BeijingAccountSnapshot = gates.getValue(channel).withLock {
        require(channel.requestSource != null)
        val revision = synchronized(this) { invalidate(channel); revisions.getValue(channel) }
        val account = source.loadAccount(channel)
        if (account.channel != channel || account.accountId.isBlank()) throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
        synchronized(this) {
            if (revision != revisions[channel]) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
            checks[channel] = Checked(account, now())
        }
        account
    }

    @Synchronized fun save(route: HospitalRoute, hospitalName: String, account: BeijingAccountSnapshot,
        patient: BeijingPatient, card: BeijingPatientCard): BeijingPatientSelection {
        val current = checked(route.channel) ?: error("连接校验已过期，请重新核验")
        check(current === account && patient in current.patients && card in patient.cards) { "连接或就诊人已变化，请重新核验后选择" }
        require(hospitalName.isNotBlank())
        // Retain only the chosen card. The full account list is never written to disk.
        val selectedPatient = BeijingPatient(patient.id, patient.displayName, patient.identityCard, patient.identityCardType,
            patient.patientType, patient.faceVerification, patient.virtualPhone, listOf(card))
        val selection = BeijingPatientSelection(UUID.randomUUID().toString(), route.channel, route.hospitalId, hospitalName,
            route.campusId, current.accountId, selectedPatient, card)
        // Old tasks keep immutable selection versions, including their original card.
        val rows = readSelections() + selection
        vault.write("beijing-patient-selections", json.encodeToString(rows))
        return selection
    }

    @Synchronized fun saved(route: HospitalRoute): BeijingPatientSelection? = readSelections().lastOrNull {
        it.channel == route.channel && it.hospitalId == route.hospitalId && it.campusId == route.campusId
    }

    @Synchronized fun load(ref: PatientRef): BeijingPatientSelection = readSelections().singleOrNull { it.reference == ref }
        ?: throw HospitalException("未找到京通就诊连接，请重新连接并选择就诊人")

    fun binding(ref: PatientRef): ConnectionBinding = HospitalIdentity(vault).beijing(load(ref))

    suspend fun validate(ref: PatientRef): BeijingPatientSelection {
        val selection = load(ref)
        refresh(selection.channel)
        if (!isCurrent(selection)) throw HospitalException("京通账户、就诊人或就诊卡已变化，请重新核验原连接", reconnectRequired = true)
        return selection
    }

    /** Requires a fresh in-process check; saved data alone is never current identity evidence. */
    @Synchronized fun isCurrent(selection: BeijingPatientSelection): Boolean {
        val account = checked(selection.channel) ?: return false
        if (account.accountId != selection.accountId) return false
        val patient = account.patients.singleOrNull { it.id == selection.patient.id } ?: return false
        return patient.identityCard == selection.patient.identityCard && patient.identityCardType == selection.patient.identityCardType &&
            patient.patientType == selection.patient.patientType && patient.faceVerification == selection.patient.faceVerification &&
            patient.virtualPhone == selection.patient.virtualPhone && patient.cards.any {
                it.number == selection.card.number && it.cardType == selection.card.cardType && it.medicareType == selection.card.medicareType
            }
    }

    private fun checked(channel: RegistrationChannel): BeijingAccountSnapshot? = checks[channel]?.takeIf {
        val time = now(); !time.isBefore(it.at) && time.isBefore(it.at.plusSeconds(300))
    }?.account
    private fun readSelections(): List<BeijingPatientSelection> = vault.read("beijing-patient-selections")
        ?.let { json.decodeFromString(it) } ?: emptyList()
}
