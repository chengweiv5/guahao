package cn.guahao.hospital.beijing

import cn.guahao.core.*
import cn.guahao.storage.SecretStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BeijingConnectionRepositoryTest {
    private class MemorySecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun read(name: String) = values[name]
        override fun write(name: String, text: String) { values[name] = text }
    }
    private val jt = RegistrationChannel.JINGTONG
    private val route = HospitalRoute("hospital-a", jt)
    private fun account(channel: RegistrationChannel = jt, user: String = "synthetic-account", number: String = "synthetic-card") =
        BeijingAccountSnapshot(channel, user, listOf(BeijingPatient("synthetic-patient", "测*", "synthetic-doc", 1,
            0, "wait_verify", false, listOf(BeijingPatientCard(number, "****1234", 1, 3, "自费", true)))))
    private fun save(repo: BeijingConnectionRepository, account: BeijingAccountSnapshot, route: HospitalRoute = this.route) =
        repo.save(route, "测试医院", account, account.patients.single(), account.patients.single().cards.single())

    @Test fun explicitSelectionSurvivesStorageButVerificationDoesNotSurviveRestart() = runBlocking {
        val vault = MemorySecrets(); val source = BeijingAccountSource { account(it) }
        val repo = BeijingConnectionRepository(vault, source)
        val selection = save(repo, repo.refresh(jt))
        assertTrue(repo.isCurrent(selection))
        assertFalse(selection.toString().contains("synthetic"))
        assertEquals(selection.id, repo.saved(route)!!.id)
        assertEquals(setOf("beijing-patient-selections"), vault.values.keys)
        val restarted = BeijingConnectionRepository(vault, source)
        assertFalse(restarted.isCurrent(restarted.saved(route)!!))
        restarted.refresh(jt)
        assertTrue(restarted.isCurrent(selection))
    }

    @Test fun changedAccountOrCardCannotUseOldSelectionAndHospitalChannelsStaySeparate() = runBlocking {
        var account = account()
        val repo = BeijingConnectionRepository(MemorySecrets(), BeijingAccountSource { account })
        val original = repo.refresh(jt); val selection = save(repo, original)
        for (fresh in listOf(account(user = "other-account"), account(number = "different-card"))) {
            account = fresh; repo.refresh(jt)
            assertFalse(repo.isCurrent(selection))
            try { save(repo, original); fail() } catch (_: IllegalStateException) { }
        }
        assertNull(repo.saved(HospitalRoute("hospital-b", jt)))
        assertNull(repo.saved(HospitalRoute("hospital-a", RegistrationChannel.BEIJING_114)))
        try { save(repo, account, HospitalRoute("hospital-a", RegistrationChannel.BEIJING_114)); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(selection.id, repo.saved(route)!!.id)
    }

    @Test fun expiryAndClockRollbackRequireReverification() = runBlocking {
        val start = Instant.parse("2026-09-26T00:00:00Z"); var time = start
        val repo = BeijingConnectionRepository(MemorySecrets(), BeijingAccountSource { account() }) { time }
        val account = repo.refresh(jt); val selection = save(repo, account)
        for (newTime in listOf(start.minusSeconds(1), start.plusSeconds(300))) {
            time = newTime
            assertFalse(repo.isCurrent(selection))
            try { save(repo, account); fail() } catch (_: IllegalStateException) { }
        }
    }

    @Test fun failureAndCancellationInvalidateUsabilityWithoutDestroyingSavedSelection() = runBlocking {
        var failure: Exception? = null
        val repo = BeijingConnectionRepository(MemorySecrets(), BeijingAccountSource { failure?.let { throw it }; account() })
        val selection = save(repo, repo.refresh(jt))
        for (error in listOf(BeijingQueryException(BeijingFailureKind.RECONNECT), CancellationException())) {
            failure = error
            try { repo.refresh(jt); fail() } catch (_: Exception) { }
            assertFalse(repo.isCurrent(selection))
            assertEquals(selection.id, repo.saved(route)!!.id)
            failure = null; repo.refresh(jt)
            assertTrue(repo.isCurrent(selection))
        }
    }

    @Test fun openingOfficialPageDuringReadPreventsLateRevalidation() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = BeijingConnectionRepository(MemorySecrets(), BeijingAccountSource {
            started.complete(Unit); release.await(); account()
        })
        val result = async { runCatching { repo.refresh(jt) } }
        started.await(); repo.invalidate(jt); release.complete(Unit)
        assertEquals(BeijingFailureKind.RECONNECT, (result.await().exceptionOrNull() as BeijingQueryException).kind)
    }
}
