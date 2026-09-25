package cn.guahao

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.guahao.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only regression against the user's existing task state; never sends a hospital request. */
@RunWith(AndroidJUnit4::class)
class HospitalImportGuardTest {
    @Test fun existingTasksDoNotBlockFirstHospitalImport() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = instrumentation.targetContext.graph
        val before = graph.store.all()
        val blockers = before.filter {
            it.phase in setOf(TaskPhase.WAITING, TaskPhase.SEARCHING, TaskPhase.SUBMITTING, TaskPhase.RECONCILING) ||
                (it.attempt != null && it.order == null && !it.manuallyResolved)
        }
        fun demo(r: TaskRecord) = r.task.demo || r.task.condition.patient.sessionId == "demo"
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("task_count", before.size)
            putInt("demo_blockers", blockers.count(::demo))
            putInt("real_blockers", blockers.count { !demo(it) })
            putInt("demo_unresolved", blockers.count { demo(it) && it.attempt != null && it.order == null && !it.manuallyResolved })
            putBoolean("has_current_session", graph.sessions.current() != null)
        })
        try {
            graph.importSession("invalid-link-for-local-validation")
            fail("Invalid link accepted")
        } catch (e: HospitalException) {
            // Reaching local link validation proves demo state did not block connection.
            assertTrue(e.safeMessage.contains("HTTPS 页面链接"))
        } catch (e: IllegalStateException) {
            fail("Task records blocked hospital connection: ${e.message}")
        } finally {
            assertEquals("Probe must retain all task data", before, graph.store.all())
        }
    }
}
