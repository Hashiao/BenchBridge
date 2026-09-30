package io.benchbridge.app

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import androidx.lifecycle.ViewModelProvider
import io.benchbridge.app.diagnostics.NativeProbe
import io.benchbridge.app.ram.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RamIntegrationTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var client: RunnerClient
    private lateinit var store: RunStore
    private val ownedRuns = mutableListOf<String>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        client = RunnerClient(context)
        store = RunStore(context)
    }
    @After fun cleanup() = runBlocking {
        ownedRuns.forEach { id ->
            runCatching {
                client.service().cancel(id, "INSTRUMENTATION_CLEANUP")
                awaitTerminal(id)
            }
            store.delete(id)
        }
        client.close()
    }

    private suspend fun start(config: RamConfig): String {
        val reply = JSONObject(client.service().startRam(config.toJson().toString()))
        assertTrue(reply.toString(), reply.optBoolean("accepted"))
        assertNotEquals("Work must run in a separate process", Process.myPid(), reply.getInt("worker_pid"))
        return reply.getString("run_id").also { ownedRuns += it }
    }
    private suspend fun awaitSnapshot(id: String, predicate: (JSONObject) -> Boolean): JSONObject = withTimeout(45000) {
        var result: JSONObject
        do {
            result = JSONObject(client.service().snapshot(id))
            if (predicate(result)) break
            delay(40)
        } while (true)
        result
    }
    private suspend fun awaitTerminal(id: String) = awaitSnapshot(id) { it.optString("state") in RamResults.terminalStates }

    @Test fun allSixKernelsPreserveCountsUnitsAndThreadPartitioning() = runBlocking {
        assertTrue(NativeProbe.inspect().passed)
        val config = RamConfig(workingSetMiB = 4, latencySetMiB = 2, threads = 3,
            warmupMs = 50, durationMs = 150, rounds = 2, latencyRounds = 2, cooldownMs = 0,
            presetId = "instrumentation-six-kernels-v1")
        val id = start(config)
        val report = awaitTerminal(id)
        assertEquals(report.toString(), "COMPLETED", report.getString("state"))
        assertEquals(12, report.getInt("completed_rounds"))
        RamKind.entries.forEach { kind ->
            val samples = RamResults.validRounds(report, kind.code)
            assertEquals(kind.title, 2, samples.size)
            samples.forEach { sample ->
                assertEquals(config.bytes(kind.code), sample.getLong("working_set_bytes"))
                assertEquals(config.threads(kind.code), sample.getInt("threads"))
                assertTrue(sample.getLong("elapsed_ns") >= config.durationMs * 1000000L)
                assertTrue(sample.getLong("operations") > 0)
                assertEquals(sample.getLong("operations") * 8, sample.getLong("payload_bytes"))
                val factor = if (kind == RamKind.COPY) 2 else 1
                assertEquals(sample.getLong("payload_bytes") * factor, sample.getLong("logical_bytes"))
                assertTrue(RamResults.value(sample).isFinite() && RamResults.value(sample) > 0)
            }
        }
        val saved = store.read(id)!!
        assertEquals("COMPLETED", saved.getString("state"))
        assertEquals(12, saved.getJSONArray("rounds").length())
        assertEquals(64, saved.getString("config_sha256").length)
    }

    @Test fun cancellingStopsTheActiveRoundAndRejectsConcurrentRuns() = runBlocking {
        val config = RamConfig(kinds = listOf(1), workingSetMiB = 16, threads = 2,
            warmupMs = 0, durationMs = 5000, rounds = 3, cooldownMs = 0, presetId = "instrumentation-cancel-v1")
        val id = start(config)
        awaitSnapshot(id) { it.optString("phase") == "MEASURING" }
        val duplicate = JSONObject(client.service().startRam(config.toJson().toString()))
        assertFalse(duplicate.getBoolean("accepted"))
        assertEquals(id, duplicate.getString("run_id"))
        val start = SystemClock.elapsedRealtime()
        client.service().cancel(id, "RUN_CANCELLED_TEST")
        val result = awaitTerminal(id)
        assertEquals("CANCELLED", result.getString("state"))
        assertTrue("Cancellation must converge", SystemClock.elapsedRealtime() - start < 3000)
        assertTrue(RamResults.validRounds(result, 1).isEmpty())
        assertEquals(0, result.getInt("completed_rounds"))
        assertEquals("CANCELLED", store.read(id)!!.getString("state"))
    }

    @Test fun validationRejectsOversizedAndMalformedPlansBeforeAllocation() = runBlocking {
        val invalid = JSONObject(client.service().startRam(RamConfig(threads = 0).toJson().toString()))
        assertFalse(invalid.getBoolean("accepted"))
        val tooLarge = JSONObject(client.service().startRam(RamConfig(kinds = listOf(3), workingSetMiB = 2048).toJson().toString()))
        assertFalse(tooLarge.getBoolean("accepted"))
        assertTrue(tooLarge.getString("error").contains("RAM_BUDGET"))
        val handle = RamNative.createSession()
        try {
            val result = JSONObject(RamNative.runRound(handle, 0, 1024, 1, 0, 100, 1))
            assertEquals("FAILED", result.getString("status"))
            assertEquals("PARAM_INVALID", result.getString("error"))
        } finally { RamNative.releaseSession(handle) }
    }

    @Test fun interruptedRoundsAreExcludedAndCopyStatisticsUseLogicalBytes() {
        val samples = JSONArray()
        listOf(1000000000L, 2000000000L, 3000000000L).forEach { bytes ->
            samples.put(JSONObject().put("kind", 2).put("status", "COMPLETED").put("verified", true)
                .put("payload_bytes", bytes).put("logical_bytes", bytes * 2)
                .put("operations", bytes / 8).put("elapsed_ns", 1000000000L))
        }
        samples.put(JSONObject().put("kind", 2).put("status", "INTERRUPTED").put("verified", true)
            .put("operations", 1).put("logical_bytes", 999999999999L).put("elapsed_ns", 1))
        val stats = RamResults.statistics(JSONObject().put("rounds", samples), 2)!!
        assertEquals(3, stats.count)
        assertEquals(4.0, stats.median, 0.000001)
        assertEquals(2.0, stats.minimum, 0.000001)
        assertEquals(6.0, stats.maximum, 0.000001)
        assertEquals(50.0, stats.cvPercent!!, 0.000001)
    }

    @Test fun workerDeathPreservesCompletedRoundsAndUiProcessSurvives() = runBlocking {
        val config = RamConfig(kinds = listOf(0, 1), workingSetMiB = 4, warmupMs = 0,
            durationMs = 1200, rounds = 1, cooldownMs = 0, presetId = "instrumentation-worker-death-v1")
        val id = start(config)
        awaitSnapshot(id) { it.optInt("completed_rounds") == 1 && it.optInt("current_kind", -1) == 1 && it.optString("phase") == "MEASURING" }
        val oldPid = JSONObject(client.service().capabilities()).getInt("worker_pid")
        assertNotEquals(Process.myPid(), oldPid)
        Process.killProcess(oldPid)
        withTimeout(30000) {
            while (true) {
                try {
                    val caps = JSONObject(client.service().capabilities())
                    if (caps.getInt("worker_pid") != oldPid) break
                } catch (_: android.os.RemoteException) { }
                delay(100)
            }
        }
        val restored = store.read(id)!!
        assertEquals(restored.toString(), "INTERRUPTED", restored.getString("state"))
        assertEquals(1, RamResults.validRounds(restored, 0).size)
        assertTrue(RamResults.validRounds(restored, 1).isEmpty())
        assertTrue(NativeProbe.inspect().passed)
    }

    @Test fun visibleStartButtonRunsTheQuickPresetToCompletion() {
        val previous = store.list().map { it.getString("run_id") }.toSet()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wait(Until.hasObject(By.res("ram_page")), 5000)
        device.wait(Until.findObject(By.res("ram_settings")), 5000).click()
        val quick = device.wait(Until.findObject(By.res("ram_quick")), 5000)
        assertNotNull(quick)
        quick.click()
        device.wait(Until.findObject(By.res("settings_done")), 5000).click()
        device.wait(Until.findObject(By.res("ram_start")), 5000).click()
        var finished: JSONObject? = null
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            val record = store.list().firstOrNull { it.getString("run_id") !in previous && it.optString("state") in RamResults.terminalStates }
            finished = record
            if (record != null) break
            Thread.sleep(100)
        }
        assertNotNull("UI-started run should finish", finished)
        val report = finished!!
        ownedRuns += report.getString("run_id")
        assertEquals(report.toString(), "COMPLETED", report.getString("state"))
        assertEquals(6, report.getInt("completed_rounds"))
        // 记录可能先于界面下一次状态刷新完成持久化。
        // Persistence can finish before the UI's next status refresh.
        val history = device.wait(Until.findObject(By.res("tab_2").enabled(true)), 5000)
        assertNotNull(history)
        history.click()
        assertTrue(device.wait(Until.hasObject(By.res("history_page")), 5000))
    }

    private suspend fun uiRun(model: RamViewModel): String = withTimeout(15000) {
        while (model.state.value.report == null) {
            model.state.value.error?.let { error(it) }
            delay(20)
        }
        model.state.value.report!!.getString("run_id").also { ownedRuns += it }
    }

    @Test fun sleepingScreenKeepsTheRunAliveAndReleasesWakeLockAfterward() = runBlocking {
        lateinit var model: RamViewModel
        scenario.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[RamViewModel::class.java]
            model.configure(RamConfig(kinds = listOf(0), workingSetMiB = 8, warmupMs = 0,
                durationMs = 5000, presetId = "instrumentation-background-v1"))
            model.start()
        }
        val id = uiRun(model)
        awaitSnapshot(id) { it.optString("phase") == "MEASURING" }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(JSONObject(client.service().capabilities()).getBoolean("wake_lock_held"))
        try {
            device.pressHome(); device.sleep()
            val result = awaitTerminal(id)
            assertEquals(result.toString(), "COMPLETED", result.getString("state"))
            val sample = RamResults.validRounds(result, 0).single()
            assertFalse(sample.getBoolean("screen_interactive_after"))
            assertTrue(sample.getBoolean("wake_lock_held"))
            assertFalse(JSONObject(client.service().capabilities()).getBoolean("wake_lock_held"))
            assertFalse(JSONObject(client.service().capabilities()).getBoolean("foreground_run"))
        } finally { device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard") }
    }

    @Test fun recreatingActivityKeepsTheSameRunWithoutStartingAnother() = runBlocking {
        lateinit var model: RamViewModel
        scenario.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[RamViewModel::class.java]
            model.configure(RamConfig(kinds = listOf(0), workingSetMiB = 8, warmupMs = 0,
                durationMs = 3500, presetId = "instrumentation-recreate-v1"))
            model.start()
        }
        val id = uiRun(model)
        awaitSnapshot(id) { it.optString("phase") == "MEASURING" }
        scenario.scenario.recreate()
        scenario.scenario.onActivity { activity -> assertSame(model, ViewModelProvider(activity)[RamViewModel::class.java]) }
        val result = awaitTerminal(id)
        assertEquals("COMPLETED", result.getString("state"))
        assertEquals(id, result.getString("run_id"))
        assertEquals(1, result.getInt("completed_rounds"))
    }

    @Test fun immediateCancelSurvivesTheStartAcknowledgementRace() = runBlocking {
        lateinit var model: RamViewModel
        scenario.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[RamViewModel::class.java]
            model.configure(RamConfig(kinds = listOf(3), workingSetMiB = 64, durationMs = 5000,
                presetId = "instrumentation-early-cancel-v1"))
            model.start()
            model.cancel("RUN_CANCELLED_EARLY_TEST")
        }
        val id = uiRun(model)
        val result = awaitTerminal(id)
        assertEquals("CANCELLED", result.getString("state"))
        assertEquals(0, result.getInt("completed_rounds"))
    }

    @Test fun tenConsecutiveRunsReleaseNativeSessionsAndKeepSeparateResults() = runBlocking {
        repeat(10) {
            val id = start(RamConfig(kinds = listOf(2), workingSetMiB = 2, threads = 2,
                warmupMs = 0, durationMs = 100, cooldownMs = 0, presetId = "instrumentation-repeat-v1"))
            val result = awaitTerminal(id)
            assertEquals("COMPLETED", result.getString("state"))
            assertEquals(1, result.getInt("completed_rounds"))
            assertEquals(id, store.read(id)!!.getString("run_id"))
        }
        assertEquals(10, ownedRuns.distinct().size)
    }
}
