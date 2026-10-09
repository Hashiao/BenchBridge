package io.benchbridge.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.benchbridge.app.ram.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AffinityDiagnosticsTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun firstWorker(sample: JSONObject) = sample.getJSONObject("affinity_diagnostics").getJSONArray("workers").getJSONObject(0)

    @Test fun nativeSuccessAndDistinctFailuresKeepWorkerEvidence() {
        val cpu = JSONObject(RamNative.capabilities()).getJSONArray("allowed_cpu_ids").getInt(0)
        val handle = RamNative.createSession()
        try {
            val reasons = listOf("", "readback_failed", "readback_not_singleton", "set_failed")
            for (fault in 0..3) {
                assertTrue(RamNative.setDiagnosticFault(handle, fault))
                val samples = listOf(
                    JSONObject(RamNative.runPinnedRound(handle, 0, longArrayOf(16384), intArrayOf(cpu), 0, 50, 1, 64)),
                    JSONObject(RamNative.runLatencyPointOnce(handle, cpu, 16384, 64, 1)))
                for (sample in samples) {
                    assertEquals(sample.toString(), if (fault == 0) "COMPLETED" else "FAILED", sample.getString("status"))
                    val worker = firstWorker(sample)
                    assertEquals(cpu, worker.getInt("requested_cpu"))
                    assertEquals(fault != 0, worker.getBoolean("test_injected"))
                    val before = worker.getJSONObject("before_context")
                    val after = worker.getJSONObject("after_context")
                    assertTrue(before.getInt("tid") > 0); assertEquals(before.getInt("tid"), after.getInt("tid"))
                    assertNotEquals(android.os.Process.myTid(), before.getInt("tid"))
                    assertTrue(after.has("cpuset")); assertTrue(after.getJSONObject("thread_status").has("errno"))
                    val readback = worker.getJSONObject("readback")
                    if (fault == 0) {
                        assertTrue(worker.isNull("failure_reason")); assertEquals(0, readback.getInt("errno"))
                        assertEquals(cpu, readback.getJSONArray("cpu_ids").getInt(0))
                        assertEquals(cpu, worker.getInt("observed_start_cpu")); assertEquals(cpu, worker.getInt("observed_end_cpu"))
                    } else {
                        assertEquals(reasons[fault], worker.getString("failure_reason"))
                        assertFalse(sample.has("logical_bytes")); assertFalse(sample.optBoolean("verified"))
                        if (fault == 1) { assertTrue(readback.getInt("errno") > 0); assertTrue(readback.isNull("cpu_ids")) }
                        if (fault == 2) { assertEquals(0, readback.getInt("errno")); assertEquals(0, readback.getInt("cpu_count")) }
                        if (fault == 3) assertTrue(worker.getJSONObject("set").getInt("errno") > 0)
                    }
                }
            }
        } finally { RamNative.releaseSession(handle) }
    }

    @Test fun calibrationFailurePersistsAndExportsWithoutScores() = runBlocking {
        val client = RunnerClient(context); val store = RunStore(context)
        var id: String? = null
        try {
            lateinit var model: RamViewModel
            scenario.scenario.onActivity { model = ViewModelProvider(it)[RamViewModel::class.java] }
            val config = RamConfig.aida64().toJson().put("diagnostic_test_fault", 1)
            val response = JSONObject(client.service().startRam(config.toString()))
            assertTrue(response.toString(), response.getBoolean("accepted"))
            val runId = response.getString("run_id"); id = runId
            val report = withTimeout(15000) {
                while (JSONObject(client.service().snapshot(runId)).optString("state") !in RamResults.terminalStates) delay(30)
                store.read(runId)!!
            }
            assertEquals("FAILED", report.getString("state"))
            assertEquals(0, report.getInt("completed_rounds")); assertEquals(0, report.getJSONArray("rounds").length())
            val cell = report.getJSONArray("cells").getJSONObject(0)
            assertEquals("FAILED", cell.getString("state"))
            val entry = cell.getJSONObject("calibration").getJSONArray("candidates").getJSONObject(0)
            assertEquals("FAILED", entry.getString("status"))
            val sample = entry.getJSONArray("samples").getJSONObject(0)
            assertEquals("AFFINITY_VERIFY_FAILED", sample.getString("error"))
            assertEquals("readback_failed", firstWorker(sample).getString("failure_reason"))
            val runtime = sample.getJSONObject("runtime_at_failure")
            assertTrue(runtime.getBoolean("foreground_run")); assertTrue(runtime.getBoolean("wake_lock_held"))
            assertEquals("before_foreground_start", report.getJSONObject("capabilities").getString("capture_stage"))
            assertEquals(sample.toString(), report.getJSONObject("failure_context").getJSONObject("sample").toString())
            assertTrue(report.getJSONObject("runtime_diagnostics").has("before_cleanup"))
            val token = model.prepareExport(report)
            val file = File(context.cacheDir, "pending_exports/$token.json")
            try {
                val exported = JSONObject(file.readText())
                assertEquals(report.getJSONObject("failure_context").toString(), exported.getJSONObject("failure_context").toString())
                File(context.cacheDir, "affinity-diagnostic-verification.json").writeText(exported.toString())
            } finally { model.exportPrepared(null, token) }
        } finally {
            id?.let { client.service().cancel(it, "DIAGNOSTIC_TEST_CLEANUP"); store.delete(it) }
            client.close()
        }
    }
}
