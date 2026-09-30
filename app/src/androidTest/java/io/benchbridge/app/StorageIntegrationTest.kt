package io.benchbridge.app

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var client: RunnerClient
    private lateinit var store: RunStore
    private lateinit var owned: OwnedStorage
    private val ids = mutableListOf<String>()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        client = RunnerClient(context); store = RunStore(context, "storage_results"); owned = OwnedStorage(context)
    }
    @After fun cleanup() = runBlocking {
        ids.forEach { id ->
            runCatching { client.service().cancel(id, "TEST_CLEANUP"); terminal(id) }
            runCatching { owned.cleanup(id) }
            store.delete(id)
        }
        client.close()
    }
    private fun tiny() = StorageConfig(fileMiB = 8, rounds = 2, warmupMs = 25, durationMs = 100,
        intervalMs = 0, writeBudgetMiB = 4096, presetId = "instrumentation-storage-v1")
    private suspend fun start(config: StorageConfig): String {
        val reply = JSONObject(client.service().startStorage(config.toJson().toString()))
        assertTrue(reply.toString(), reply.optBoolean("accepted"))
        assertNotEquals(Process.myPid(), reply.getInt("worker_pid"))
        return reply.getString("run_id").also { ids += it }
    }
    private suspend fun await(id: String, predicate: (JSONObject) -> Boolean): JSONObject = withTimeout(60000) {
        var result: JSONObject
        do { result = JSONObject(client.service().snapshot(id)); if (predicate(result)) break; delay(30) } while (true)
        result
    }
    private suspend fun terminal(id: String) = await(id) { it.optString("state") in RamResults.terminalStates }

    @Test fun fourRowsBothDirectionsHaveRealCountsLatencyAndCleanup() = runBlocking {
        val config = tiny()
        val id = start(config)
        val report = terminal(id)
        assertTrue(report.toString(), report.getString("state") in listOf("COMPLETED", "PARTIAL"))
        var successful = 0
        config.cases.forEach { case -> config.directions.forEach { direction ->
            val samples = StorageResults.valid(report, case.id, direction)
            val caps = report.getJSONObject("capabilities")
            if (case.queue > 1 && !caps.optBoolean("native_aio") || !caps.optBoolean("direct_$direction")) {
                assertEquals("UNSUPPORTED", StorageResults.samples(report, case.id, direction).single().getString("status"))
            } else {
                successful += samples.size
                assertEquals(2, samples.size)
                samples.forEach { sample ->
                    val ops = sample.getLong("operations")
                    assertTrue(ops > 0)
                    assertEquals(ops * case.blockKiB * 1024, sample.getLong("completed_bytes"))
                    assertTrue(sample.getLong("elapsed_ns") > 0)
                    assertEquals(if (case.queue > 1) "linux-native-aio" else "pread-pwrite", sample.getString("engine"))
                    assertEquals("O_DIRECT", sample.getString("cache_mode"))
                    assertTrue(sample.getInt("observed_application_qd_max") in 1..case.queue)
                    assertTrue(sample.getDouble("application_qd_mean") in 0.0..case.queue.toDouble())
                    val depths = sample.getJSONArray("per_thread_qd_time_ns").getJSONArray(0)
                    assertEquals(case.queue + 1, depths.length())
                    assertEquals(sample.getLong("elapsed_ns"), (0 until depths.length()).sumOf { depths.getLong(it) })
                    assertTrue(sample.getLong("latency_p99_ns") >= sample.getLong("latency_p95_ns"))
                    assertTrue(sample.getLong("latency_max_ns") >= sample.getLong("latency_p99_ns"))
                    val histogram = sample.getJSONArray("latency_histogram")
                    assertEquals(ops, (0 until histogram.length()).sumOf { histogram.getJSONArray(it).getLong(1) })
                    assertEquals(if (sample.getInt("round") == 1) 25 else 0, sample.getInt("warmup_ms"))
                    if (direction == "write") assertTrue(sample.getLong("flush_ns_separate") > 0)
                }
                assertEquals(samples.maxOf(StorageResults::mbps), StorageResults.mbps(StorageResults.best(report, case.id, direction)!!), 0.00001)
            }
        } }
        assertTrue("Q1 read/write must work on test device", successful >= 8)
        assertEquals(successful, report.getInt("completed_rounds"))
        assertEquals("CLEANED", report.getJSONObject("cleanup").getString("state"))
        assertFalse(owned.directory(id).exists())
        assertEquals(report.getString("state"), store.read(id)!!.getString("state"))
        val totals = report.getJSONObject("io_totals")
        assertTrue(totals.getLong("written_bytes") >= 8L * 1048576)
        assertTrue(totals.getLong("write_reserved_bytes") <= config.writeBudgetMiB * 1048576L)
    }

    @Test fun cancellationAndRamExclusionReleaseAllFiles() = runBlocking {
        val config = tiny().copy(cases = listOf(StorageCase.standard()[1]), directions = listOf("read"), durationMs = 5000)
        val id = start(config)
        await(id) { it.optString("phase") == "MEASURING" }
        val ram = JSONObject(client.service().startRam(RamConfig().toJson().toString()))
        assertFalse(ram.getBoolean("accepted")); assertEquals(id, ram.getString("run_id"))
        val duplicate = JSONObject(client.service().startStorage(config.toJson().toString()))
        assertFalse(duplicate.getBoolean("accepted"))
        client.service().cancel(id, "TEST_CANCEL")
        val report = terminal(id)
        assertEquals("CANCELLED", report.getString("state"))
        assertEquals(0, report.getInt("completed_rounds"))
        assertEquals("CLEANED", report.getJSONObject("cleanup").getString("state"))
        assertFalse(owned.directory(id).exists())
    }

    @Test fun budgetIncludesInitializationAndNeverProducesTruncatedScores() = runBlocking {
        val id = start(tiny().copy(cases = listOf(StorageCase.standard()[1]), directions = listOf("write"),
            writeBudgetMiB = 9, warmupMs = 0, durationMs = 1000))
        val report = terminal(id)
        assertEquals(report.toString(), "INTERRUPTED", report.getString("state"))
        assertEquals(0, report.getInt("completed_rounds"))
        assertTrue(report.getString("error").startsWith("WRITE_BUDGET"))
        assertTrue(report.getJSONObject("io_totals").getLong("written_bytes") in (8L * 1048576)..(9L * 1048576))
        assertFalse(owned.directory(id).exists())
    }

    @Test fun unlimitedWritesReuseOneFileAcrossRoundsWhileScreenSleeps() = runBlocking {
        val config = tiny().copy(cases = listOf(StorageCase.standard()[1]), directions = listOf("write"),
            fileMiB = 8, rounds = 2, warmupMs = 0, durationMs = 3500, writeBudgetMiB = 0)
        val id = start(config)
        await(id) { it.optString("phase") == "MEASURING" }
        assertEquals(8L * 1048576, File(owned.directory(id), "data-000.bin").length())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.sleep()
            val report = terminal(id)
            assertEquals(report.toString(), "COMPLETED", report.getString("state"))
            assertEquals(2, report.getInt("completed_rounds"))
            assertEquals(8L * 1048576, report.getLong("disk_footprint_bytes"))
            val totals = report.getJSONObject("io_totals")
            assertTrue(totals.getLong("written_bytes") > report.getLong("disk_footprint_bytes"))
            assertFalse(totals.getBoolean("budget_exhausted"))
            assertTrue(StorageResults.valid(report, config.cases.single().id, "write").any { !it.getBoolean("screen_interactive_after") })
            assertEquals("CLEANED", report.getJSONObject("cleanup").getString("state"))
            assertFalse(owned.directory(id).exists())
            assertFalse(JSONObject(client.service().capabilities()).getBoolean("wake_lock_held"))
        } finally { device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard") }
    }

    @Test fun bufferedAndMultithreadedPlansRemainExplicitAndSeparate() = runBlocking {
        val case = StorageCase("custom-q1t3", false, 128, 1, 3)
        val id = start(tiny().copy(cases = listOf(case), direct = false, rounds = 1, warmupMs = 0, durationMs = 50))
        val report = terminal(id)
        assertEquals(report.toString(), "COMPLETED", report.getString("state"))
        listOf("read", "write").forEach { direction ->
            val sample = StorageResults.valid(report, case.id, direction).single()
            assertEquals("buffered", sample.getString("cache_mode"))
            assertEquals(3, sample.getInt("threads"))
            assertTrue(sample.getInt("observed_application_qd_max") in 1..3)
        }
    }

    @Test fun oversizedQueueAndFileAreRejectedBeforeCreatingData() = runBlocking {
        val invalid = tiny().copy(cases = listOf(StorageCase("invalid", true, 4096, 64, 16)))
        assertFalse(JSONObject(client.service().startStorage(invalid.toJson().toString())).getBoolean("accepted"))
        val large = tiny().copy(fileMiB = 65536, writeBudgetMiB = 65536)
        val response = JSONObject(client.service().startStorage(large.toJson().toString()))
        assertFalse(response.getBoolean("accepted")); assertTrue(response.getString("error").contains("STORAGE_SPACE"))
    }

    @Test fun nvmeProfileUsesSixteenRealThreadsWithNativeQueues() = runBlocking {
        val config = tiny().copy(cases = StorageCase.nvme(), fileMiB = 16, rounds = 1, warmupMs = 0, durationMs = 75)
        val id = start(config)
        val report = terminal(id)
        assertEquals(report.toString(), "COMPLETED", report.getString("state"))
        assertEquals(8, report.getInt("completed_rounds"))
        val case = config.cases[2]
        for (direction in config.directions) {
            val sample = StorageResults.valid(report, case.id, direction).single()
            assertEquals(16, sample.getInt("threads"))
            assertEquals(512, sample.getInt("requested_qd"))
            assertEquals(16, sample.getJSONArray("per_thread_qd_time_ns").length())
            val workers = sample.getJSONArray("per_thread")
            assertEquals(16, workers.length())
            assertEquals(sample.getLong("operations"), (0 until workers.length()).sumOf { workers.getJSONObject(it).getLong("operations") })
            assertEquals(config.fileMiB * 1048576L / (case.blockKiB * 1024), (0 until workers.length()).sumOf { workers.getJSONObject(it).getLong("block_count") })
            assertTrue(sample.getInt("observed_application_qd_max") in 1..512)
            assertEquals("linux-native-aio", sample.getString("engine"))
        }
    }

    @Test fun cleanupRefusesUnknownFilesSymlinksAndActiveSessions() {
        val id = UUID.randomUUID().toString(); val dir = owned.create(id, "instrumentation-cleanup")
        val foreign = File(dir, "keep.txt").apply { writeText("preserve") }
        try {
            assertEquals("REFUSED", owned.cleanup(id).getString("state"))
            assertEquals("preserve", foreign.readText())
            assertTrue(foreign.delete())
            val link = File(dir, "data-000.bin")
            Os.symlink(File(dir, "owner.json").absolutePath, link.absolutePath)
            assertEquals("REFUSED", owned.cleanup(id).getString("state"))
            assertTrue(File(dir, "owner.json").exists())
            assertTrue(link.delete())
            val handle = StorageNative.createSession(dir.absolutePath, 8L * 1048576, 16L * 1048576)
            try { assertEquals("PENDING", owned.cleanup(id).getString("state")) }
            finally { StorageNative.releaseSession(handle) }
            assertEquals("CLEANED", owned.cleanup(id).getString("state"))
            assertFalse(dir.exists())
        } finally { foreign.delete(); owned.cleanup(id) }
    }

    @Test fun nativeShortReadsFailAndDoNotBecomeScores() {
        val id = UUID.randomUUID().toString()
        val dir = owned.create(id, "instrumentation-short-read")
        val handle = StorageNative.createSession(dir.absolutePath, 8L * 1048576, 16L * 1048576)
        try {
            assertEquals("COMPLETED", JSONObject(StorageNative.prepare(handle)).getString("status"))
            java.io.RandomAccessFile(File(dir, "data-000.bin"), "rw").use { it.setLength(0) }
            val result = JSONObject(StorageNative.runRound(handle, false, false, 4096, 1, 1, 0, 50, false))
            assertEquals("FAILED", result.getString("status"))
            assertFalse(result.getBoolean("verified"))
            assertEquals(0, result.getLong("operations"))
        } finally { StorageNative.releaseSession(handle); assertEquals("CLEANED", owned.cleanup(id).getString("state")) }
    }

    @Test fun workerDeathRecoversTheLedgerAndKeepsCompletedRounds() = runBlocking {
        val config = tiny().copy(cases = listOf(StorageCase.standard()[1]), directions = listOf("read"), durationMs = 1500,
            rounds = 3, warmupMs = 0)
        val id = start(config)
        await(id) { it.optInt("completed_rounds") == 1 && it.optInt("current_round") == 2 && it.optString("phase") == "MEASURING" }
        val pid = JSONObject(client.service().capabilities()).getInt("worker_pid")
        assertNotEquals(Process.myPid(), pid)
        Process.killProcess(pid)
        withTimeout(30000) {
            while (true) {
                try { if (JSONObject(client.service().capabilities()).getInt("worker_pid") != pid) break }
                catch (_: android.os.RemoteException) { }
                delay(100)
            }
        }
        val saved = store.read(id)!!
        assertEquals("INTERRUPTED", saved.getString("state"))
        assertEquals(1, saved.getInt("completed_rounds"))
        assertEquals("CLEANED", saved.getJSONObject("cleanup").getString("state"))
        assertFalse(owned.directory(id).exists())
    }

    @Test fun visibleRomQuickPresetFinishesAndOpensHistory() {
        val previous = store.list().map { it.getString("run_id") }.toSet()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val tab = device.wait(Until.findObject(By.res("tab_1")), 5000)
        assertNotNull(tab); tab.click()
        device.wait(Until.findObject(By.res("storage_settings")), 5000).click()
        val quick = device.wait(Until.findObject(By.res("storage_quick")), 5000)
        assertNotNull(quick); quick.click()
        device.wait(Until.findObject(By.res("settings_done")), 5000).click()
        device.wait(Until.findObject(By.res("storage_start")), 5000).click()
        val deadline = SystemClock.elapsedRealtime() + 60000
        var finished: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            finished = store.list().firstOrNull { it.getString("run_id") !in previous && it.optString("state") in RamResults.terminalStates }
            if (finished != null) break
            Thread.sleep(100)
        }
        assertNotNull("UI-started storage run must finish", finished)
        val report = finished!!; ids += report.getString("run_id")
        assertEquals(report.toString(), "COMPLETED", report.getString("state"))
        assertEquals(8, report.getInt("completed_rounds"))
        assertEquals("CLEANED", report.getJSONObject("cleanup").getString("state"))
        val history = device.wait(Until.findObject(By.res("tab_2").enabled(true)), 5000)
        assertNotNull(history); history.click()
        assertTrue(device.wait(Until.hasObject(By.res("history_page")), 5000))
    }
}
