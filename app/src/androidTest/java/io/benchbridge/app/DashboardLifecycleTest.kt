package io.benchbridge.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
class DashboardLifecycleTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var model: RamViewModel
    private lateinit var client: RunnerClient
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private val ownRuns = mutableListOf<Pair<String, Boolean>>()
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        client = RunnerClient(context)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        scenario.scenario.onActivity { model = ViewModelProvider(it)[RamViewModel::class.java] }
        withTimeout(15000) { while (model.state.value.capabilities == null) delay(20) }
    }
    @After fun cleanup() = runBlocking {
        ownRuns.forEach { (id, disk) ->
            runCatching {
                client.service().cancel(id, "TEST_CLEANUP")
                withTimeout(10000) { while (JSONObject(client.service().snapshot(id)).optString("state") !in RamResults.terminalStates) delay(30) }
                RunStore(context, if (disk) "storage_results" else "ram_results").delete(id)
            }
        }
        client.close()
    }
    private fun tap(tag: String) = checkNotNull(device.wait(Until.findObject(By.res(tag)), 5000)) { tag }.click()
    private fun assertBoard(tags: List<String>) {
        device.waitForIdle()
        val board = checkNotNull(device.wait(Until.findObject(By.res("result_board")), 5000)).visibleBounds
        assertTrue(board.height() > 100)
        tags.forEach { tag ->
            val row = checkNotNull(device.findObject(By.res(tag))) { "Missing $tag" }.visibleBounds
            assertTrue("$tag must be completely inside the visible board: $row in $board", board.contains(row))
            assertTrue("$tag must have space for its result and settings", row.height() >= 48)
        }
        assertFalse("The result page must not require scrolling", device.hasObject(By.scrollable(true)))
        assertTrue(device.hasObject(By.res("result_device")))
    }
    private suspend fun finished(disk: Boolean): JSONObject = withTimeout(60000) {
        while (true) {
            val state = model.state.value
            val report = if (disk) state.storageReport else state.report
            if (report != null) {
                val pair = report.getString("run_id") to disk
                if (pair !in ownRuns) ownRuns += pair
                if (!state.running) {
                    assertEquals(report.toString(), "COMPLETED", report.optString("state"))
                    return@withTimeout report
                }
            } else state.error?.let { error(it) }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    @Test fun defaultBoardsShowEveryRowWithoutExposingSettings() {
        assertBoard(listOf("L1", "L2", "L3", "RAM").map { "matrix_row_$it" })
        assertFalse(device.hasObject(By.res("ram_aida")))
        assertTrue(device.hasObject(By.res("ram_start")))
        tap("tab_1")
        assertBoard((0..3).map { "storage_row_$it" })
        assertFalse(device.hasObject(By.res("storage_default")))
        assertTrue(device.hasObject(By.res("storage_start")))
    }
    @Test fun completedRamAndRomFitAndShareCompletePngs() = runBlocking {
        tap("ram_settings"); tap("ram_quick"); tap("settings_done"); tap("ram_start")
        finished(false)
        assertBoard((0..5).map { "ram_row_$it" })
        shareAndInspect()
        tap("tab_1"); tap("storage_settings"); tap("storage_quick"); tap("settings_done"); tap("storage_start")
        finished(true)
        assertBoard((0..3).map { "storage_row_$it" })
        tap("result_unit")
        // Android 上的下拉菜单使用独立的弹出窗口语义根。
        // DropdownMenu has a separate popup semantics root on Android.
        checkNotNull(device.wait(Until.findObject(By.text("IOPS")), 5000)).click()
        assertBoard((0..3).map { "storage_row_$it" })
        shareAndInspect()
    }
    private fun shareAndInspect() {
        val directory = File(context.cacheDir, "shared_screenshots")
        val previous = directory.listFiles()?.toSet().orEmpty()
        tap("share_screenshot")
        assertTrue("Android share chooser must open", device.wait(Until.hasObject(By.pkg("com.android.intentresolver")), 10000))
        val file = directory.listFiles()!!.single { it !in previous }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            assertEquals("image/png", context.contentResolver.getType(uri))
            val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }!!
            assertEquals(device.displayWidth, bitmap.width)
            assertTrue(bitmap.height >= device.displayHeight * 0.9)
            bitmap.recycle()
        } finally { device.pressBack(); file.delete() }
        assertTrue(device.wait(Until.hasObject(By.res("result_board")), 5000))
    }
    @Test fun aNewControllerReattachesToTheSameForegroundRun() = runBlocking {
        val response = JSONObject(client.service().startRam(RamConfig(kinds = listOf(0), workingSetMiB = 8,
            warmupMs = 0, durationMs = 5000).toJson().toString()))
        assertTrue(response.toString(), response.getBoolean("accepted"))
        val id = response.getString("run_id"); ownRuns += id to false
        val viewStore = ViewModelStore()
        lateinit var reattached: RamViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            reattached = ViewModelProvider(viewStore, ViewModelProvider.AndroidViewModelFactory(context as Application))[RamViewModel::class.java]
        }
        try {
            withTimeout(15000) { while (reattached.state.value.report == null) delay(20) }
            assertEquals(id, reattached.state.value.report!!.getString("run_id"))
            withTimeout(15000) { while (reattached.state.value.running) delay(20) }
            assertEquals("COMPLETED", reattached.state.value.report!!.getString("state"))
            assertFalse(JSONObject(client.service().capabilities()).getBoolean("foreground_run"))
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { viewStore.clear() } }
    }
    @Test fun notificationStopReleasesWakeLockAndScreenFlag() = runBlocking {
        scenario.scenario.onActivity {
            model.configure(RamConfig(kinds = listOf(0), workingSetMiB = 8, warmupMs = 0, durationMs = 15000))
            model.start()
        }
        withTimeout(15000) { while (model.state.value.report == null) delay(20) }
        val id = model.state.value.report!!.getString("run_id"); ownRuns += id to false
        withTimeout(15000) { while (model.state.value.report!!.optString("phase") != "MEASURING") delay(20) }
        scenario.scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(notifications.any { it.id == 71 })
        notifications.single { it.id == 71 }.notification.actions.single().actionIntent.send()
        withTimeout(15000) { while (model.state.value.running) delay(25) }
        assertEquals("CANCELLED", model.state.value.report!!.getString("state"))
        assertFalse(JSONObject(client.service().capabilities()).getBoolean("wake_lock_held"))
        device.waitForIdle()
        scenario.scenario.onActivity { assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 71 })
    }

    @Test fun largeReportExportKeepsPayloadOutOfActivitySavedState() = runBlocking {
        scenario.scenario.onActivity {
            model.configureStorage(StorageConfig(cases = listOf(StorageCase.standard()[1]), directions = listOf("read"),
                fileMiB = 8, rounds = 1, warmupMs = 0, durationMs = 75, intervalMs = 0))
            model.startStorage()
        }
        val report = finished(true)
        // 为真实短测记录添加测试专用大字段，复现保存状态的 Binder 容量限制。
        // Add a large test-only field to a measured record to reproduce the saved-state Binder limit.
        val large = JSONObject(report.toString()).put("export_regression_payload", "x".repeat(700000))
        scenario.scenario.onActivity { model.selectHistory(large) }
        tap("tab_2"); tap("result_details")
        tap("copy_scores")
        scenario.scenario.onActivity {
            val copied = it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.length < 8000)
            assertTrue(copied.contains("Q1")); assertTrue(copied.contains("T1")); assertTrue(copied.contains("1 MiB"))
            assertFalse(copied.contains("export_regression_payload"))
        }
        checkNotNull(device.wait(Until.findObject(By.text("导出 JSON")), 5000)).click()
        val save = checkNotNull(device.wait(Until.findObject(By.res("android:id/button1")), 10000))
        val name = device.findObject(By.clazz("android.widget.EditText")).text
        assertTrue(name.matches(Regex("BenchBridge_ROM_[0-9a-f]{8}\\.json")))
        // 原实现将大报告放入保存状态，在外部选择器触发 activityStopped 时崩溃。
        // The former large saved-state payload crashed activityStopped when the external picker opened.
        save.click()
        assertTrue(device.wait(Until.hasObject(By.res("details_page")), 10000))
        var exported: JSONObject? = null
        withTimeout(10000) {
            while (exported == null) {
                exported = runCatching { JSONObject(device.executeShellCommand("cat /sdcard/Download/$name")) }.getOrNull()
                if (exported == null) delay(100)
            }
        }
        assertEquals(report.getString("run_id"), exported!!.getString("run_id"))
        assertEquals(700000, exported!!.getString("export_regression_payload").length)
        assertTrue(File(context.cacheDir, "pending_exports").listFiles().orEmpty().isEmpty())
        device.executeShellCommand("rm /sdcard/Download/$name")
        Unit
    }
}
