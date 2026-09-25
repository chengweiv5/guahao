package cn.guahao.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class RegistrationPlatformTest {
    private val binding = ConnectionBinding("hospital", "医院", RegistrationChannel.BEIJING_114.id, "114",
        "account", "patient", "connection", "version", SubmissionScope(RegistrationChannel.BEIJING_114.id, "account"))

    @Test fun sameHospitalAndPatientCannotCrossChannelsOrCampuses() {
        assertTrue(HospitalRoute("hospital", RegistrationChannel.BEIJING_114).accepts(binding))
        assertFalse(HospitalRoute("hospital", RegistrationChannel.JINGTONG).accepts(binding))
        assertFalse(binding.samePrincipal(binding.copy(providerId = RegistrationChannel.JINGTONG.id)))
        assertFalse(binding.samePrincipal(binding.copy(campusId = "east")))
        assertFalse(HospitalRoute("hospital", RegistrationChannel.BEIJING_114, "east").accepts(binding))
    }

    @Test fun queryEvidenceAloneCannotEnableAutomaticSubmission() {
        val readOnly = PlatformCapabilities(nativeQueriesVerified = true)
        assertFalse(readOnly.canCreateAutomaticTask)
        assertFalse(readOnly.copy(authenticationVerified = true, patientsVerified = true, submissionVerified = true).canCreateAutomaticTask)
        assertTrue(PlatformCapabilities(true, true, true, true, true).canCreateAutomaticTask)
    }

    @Test fun legacyBindingsDecodeWithoutChangingAccountScope() {
        val old = """{"hospitalId":"hospital","hospitalName":"医院","providerId":"psc-youan","providerName":"服务号","accountKey":"account","patientKey":"patient","connectionId":"connection","credentialVersionId":"version","submissionScope":{"providerId":"psc-youan","accountKey":"account"}}"""
        val restored = Json.decodeFromString<ConnectionBinding>(old)
        assertNull(restored.campusId)
        assertNull(restored.campusName)
        assertEquals(SubmissionScope("psc-youan", "account"), restored.submissionScope)
        assertTrue(restored.samePrincipal(restored.copy(credentialVersionId = "new")))
    }
}
