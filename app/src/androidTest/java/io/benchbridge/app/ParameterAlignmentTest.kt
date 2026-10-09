package io.benchbridge.app

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.*
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
class ParameterAlignmentTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var model: RamViewModel
    private lateinit var client: RunnerClient
    private lateinit var ramStore: RunStore
    private lateinit var diskStore: RunStore
    private lateinit var device: UiDevice
    private val ownRam = mutableListOf<String>()
    private val ownDisk = mutableListOf<String>()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        client = RunnerClient(context); ramStore = RunStore(context); diskStore = RunStore(context, "storage_results")
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        scenario.scenario.onActivity { model = ViewModelProvider(it)[RamViewModel::class.java] }
        withTimeout(15000) { while (model.state.value.capabilities == null) delay(20) }
    }
    @After fun cleanup() = runBlocking {
        for (id in ownDisk + ownRam) {
            runCatching {
                client.service().cancel(id, "TEST_CLEANUP")
                withTimeout(10000) { while (JSONObject(client.service().snapshot(id)).optString("state") !in RamResults.terminalStates) delay(30) }
            }
        }
        ownDisk.forEach(diskStore::delete); ownRam.forEach(ramStore::delete)
        client.close()
    }
    private fun node(id: String, scroll: Boolean = false): UiObject2 {
        fun gesture(up: Boolean, small: Boolean = false) {
            val pane = device.findObject(By.scrollable(true)) ?: return
            val bounds = pane.visibleBounds
            val distance = bounds.height() * (if (small) 0.22 else 0.60)
            val top = bounds.centerY() - (distance / 2).toInt()
            val bottom = bounds.centerY() + (distance / 2).toInt()
            device.swipe(bounds.centerX(), if (up) top else bottom, bounds.centerX(), if (up) bottom else top, 25)
            device.waitForIdle()
        }
        fun visible(): UiObject2? {
            var result = device.findObject(By.res(id)) ?: return null
            if (!scroll) return result
            // 等滚动后的控件位置稳定再点击，防止使用滑动动画中的旧坐标。
            // Wait for stable post-scroll bounds instead of tapping stale animation coordinates.
            val before = result.visibleBounds
            SystemClock.sleep(150)
            result = device.findObject(By.res(id)) ?: return null
            if (before != result.visibleBounds) return null
            val pane = device.findObject(By.scrollable(true))?.visibleBounds ?: return result
            val bounds = result.visibleBounds
            // 被视口裁切的选项仍可能报告完整语义边界，中心点可能落到固定底栏下方。
            // A clipped chip may retain full semantics bounds, placing its centre beneath the fixed footer.
            if (bounds.top < pane.top + 12) { gesture(up = true, small = true); return null }
            if (bounds.bottom > pane.bottom - 12) { gesture(up = false, small = true); return null }
            return result
        }
        repeat(3) { visible()?.let { return it } }
        if (scroll) {
            // 设置卡是单个较高的列表项，索引不变时旧 UiScrollable 可能提前停止。
            // The settings card is one tall item; legacy UiScrollable may stop while its index stays unchanged.
            // 使用有界滑动，并检查节点的实际可见区域。
            // Use bounded gestures and check each node's visible bounds.
            val upFirst = id in setOf("storage_default", "storage_nvme", "storage_quick", "ram_aida", "ram_quick") ||
                id.startsWith("ram_working_") || id.startsWith("ram_bandwidth_rounds_") || id.startsWith("storage_file_")
            for (up in listOf(upFirst, !upFirst)) repeat(12) {
                repeat(3) { visible()?.let { return it } }
                gesture(up)
            }
        }
        return checkNotNull(device.wait(Until.findObject(By.res(id)), 5000)) { "Missing UI node $id" }
    }
    private suspend fun uiTerminal(disk: Boolean): JSONObject = withTimeout(30000) {
        while (true) {
            val state = model.state.value
            val report = if (disk) state.storageReport else state.report
            if (report != null) {
                val ids = if (disk) ownDisk else ownRam
                if (report.getString("run_id") !in ids) ids += report.getString("run_id")
                if (!state.running && report.optString("state") in RamResults.terminalStates) return@withTimeout report
            }
            if (report == null && state.error != null) error(state.error)
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test fun changingDiskPresetClearsOldScoresAndKeepsItsHistory() = runBlocking {
        node("tab_1").click()
        scenario.scenario.onActivity {
            model.configureStorage(StorageConfig(cases = listOf(StorageCase.standard()[1]), directions = listOf("read"),
                fileMiB = 8, rounds = 1, warmupMs = 0, durationMs = 75, intervalMs = 0, writeBudgetMiB = 8))
            model.startStorage()
        }
        val report = uiTerminal(true)
        assertEquals("COMPLETED", report.getString("state"))
        val id = report.getString("run_id")
        val original = diskStore.read(id)!!.toString()
        node("storage_settings").click()
        node("storage_default", true).click()
        device.waitForIdle()
        assertNull("Changing the plan must not relabel old scores", model.state.value.storageReport)
        val config = model.state.value.storageConfig
        assertEquals(1024, config.fileMiB)
        assertEquals(3, config.rounds)
        assertEquals(5000, config.durationMs); assertEquals(5000, config.warmupMs); assertEquals(5000, config.intervalMs)
        assertEquals(listOf(1024, 1024, 4, 4), config.cases.map { it.blockKiB })
        assertEquals(listOf(8, 1, 32, 1), config.cases.map { it.queue })
        assertTrue(config.cases.all { it.threads == 1 })
        assertEquals(0, config.writeBudgetMiB)
        node("settings_done").click()
        assertEquals("测试文件：1 GiB", node("storage_result_file", true).text)
        assertEquals("块 1 MiB", node("storage_row_block_seq1m-q8t1", true).text)
        assertEquals(original, diskStore.read(id)!!.toString())
        // 手动恢复参数后，应根据实际值识别预设。
        // Recognize a restored preset from its actual parameters rather than a stale preset ID.
        node("storage_settings").click()
        node("storage_file_64", true).click()
        device.waitForIdle()
        assertEquals("Resizing the file must not enable a cumulative write limit", 0, model.state.value.storageConfig.writeBudgetMiB)
        assertEquals("storage-custom-v1", model.state.value.storageConfig.presetId)
        node("storage_file_1024", true).click()
        device.waitForIdle()
        assertEquals("diskmark-default-v1", model.state.value.storageConfig.presetId)
        assertTrue(node("storage_default", true).isChecked)
    }

    @Test fun quickDurationAndNvmeEditorShowActualActiveValues() {
        node("tab_1").click()
        node("storage_settings").click()
        node("storage_quick").click()
        node("storage_advanced", true).click()
        assertTrue(node("storage_duration_600", true).isChecked)
        assertEquals("0.6 秒", node("storage_duration_600").children.firstOrNull()?.text ?: node("storage_duration_600").text)
        node("storage_nvme", true).click()
        device.waitForIdle()
        assertEquals("diskmark-nvme-v1", model.state.value.storageConfig.presetId)
        assertTrue(node("storage_nvme").isChecked)
        node("storage_edit_rnd4k-q32t16", true).click()
        // 等待选中状态提交后再读取编辑器，避免读取上一帧。
        // Wait for the selected state before inspecting the editor, avoiding a stale frame.
        assertTrue(device.wait(Until.hasObject(By.res("storage_edit_rnd4k-q32t16").checked(true)), 5000))
        assertTrue(node("storage_block_4", true).isChecked)
        assertTrue(node("storage_queue_32", true).isChecked)
        assertTrue(node("storage_threads_16", true).isChecked)
        node("storage_threads_8").click()
        device.waitForIdle()
        val config = model.state.value.storageConfig
        assertEquals(8, config.cases.single { it.id == "rnd4k-q32t8" }.threads)
        assertEquals("storage-custom-v1", config.presetId)
        assertTrue(node("storage_threads_8").isChecked)
        assertTrue(node("storage_editor_summary", true).text.contains("T8"))
    }

    @Test fun ramBandwidthAndLatencyHaveIndependentControls() {
        node("ram_settings").click()
        assertTrue(node("ram_aida").isChecked)
        assertTrue(node("curve_include_ram").isChecked)
        assertTrue(node("ram_threads_1", true).isChecked)
        assertFalse(node("ram_threads_auto", true).isChecked)
        assertTrue(node("ram_bandwidth_rounds_3", true).isChecked)
        assertTrue(node("ram_latency_rounds_5", true).isChecked)
        node("ram_bandwidth_rounds_1", true).click()
        assertTrue(device.wait(Until.hasObject(By.res("ram_bandwidth_rounds_1").checked(true)),5000))
        assertEquals(1, model.state.value.config.rounds)
        assertEquals(5, model.state.value.config.latencyRounds)
        node("ram_latency_rounds_3", true).click()
        node("ram_working_64", true).click()
        device.waitForIdle()
        assertEquals(64, model.state.value.config.workingSetMiB)
        assertEquals("Changing bandwidth memory must not silently change latency memory", 64, model.state.value.config.latencySetMiB)
        assertEquals(1, model.state.value.config.rounds)
        assertEquals(3, model.state.value.config.latencyRounds)
    }

    @Test fun latencyOnlySummaryAndHistoryUseLatencyPlan() = runBlocking {
        scenario.scenario.onActivity {
            model.configure(RamConfig.aida64().copy(cacheMatrix = false, kinds = listOf(5), workingSetMiB = 16, latencySetMiB = 2,
                rounds = 3, latencyRounds = 2, durationMs = 75, warmupMs = 0, cooldownMs = 0))
            model.start()
        }
        val report = uiTerminal(false)
        assertEquals("COMPLETED", report.getString("state"))
        assertEquals(2, report.getInt("completed_rounds"))
        val frozen = RamConfig.fromJson(report.getJSONObject("config").toString())
        assertTrue(frozen.summary.contains("延迟：2 MiB · 1 线程 · 2 次"))
        assertTrue(frozen.summary.contains("0.075 秒"))
        assertFalse(frozen.summary.contains("带宽"))
        val rounds = report.getJSONArray("rounds")
        repeat(rounds.length()) { assertEquals(1, rounds.getJSONObject(it).getInt("threads")) }
        node("result_details").click()
        assertEquals(frozen.summary, node("ram_result_config", true).text)
        val id = report.getString("run_id")
        val original = ramStore.read(id)!!.toString()
        scenario.scenario.onActivity { model.configure(frozen.copy(latencySetMiB = 4)) }
        assertNull(model.state.value.report)
        assertEquals(original, ramStore.read(id)!!.toString())
    }

    @Test fun workerResolvesAutoThreadsAndRecordsRequestedVersusEffectivePlan() = runBlocking {
        val requested = RamConfig.aida64().copy(threads = 16, automaticThreads = true, cacheMatrix = false, kinds = listOf(0), workingSetMiB = 4,
            warmupMs = 0, durationMs = 75, rounds = 1, cooldownMs = 0)
        val reply = JSONObject(client.service().startRam(requested.toJson().toString()))
        assertTrue(reply.toString(), reply.getBoolean("accepted"))
        val id = reply.getString("run_id"); ownRam += id
        var report: JSONObject
        withTimeout(20000) {
            do { report = JSONObject(client.service().snapshot(id)); if (report.optString("state") in RamResults.terminalStates) break; delay(25) } while (true)
        }
        report = JSONObject(client.service().snapshot(id))
        assertEquals("COMPLETED", report.getString("state"))
        val expected = report.getJSONObject("capabilities").getInt("allowed_cpus").coerceIn(1, 16)
        assertEquals(16, report.getJSONObject("requested_config").getInt("threads"))
        assertEquals(expected, report.getJSONObject("config").getInt("threads"))
        assertEquals(expected, report.getJSONArray("rounds").getJSONObject(0).getInt("threads"))
        assertEquals("auto", report.getJSONObject("config").getString("thread_mode"))
    }
}
