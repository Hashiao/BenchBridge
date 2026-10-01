package io.benchbridge.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.benchbridge.app.ram.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheCurveIntegrationTest {
    @get:Rule val scenario=ActivityScenarioRule(MainActivity::class.java)
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val client=RunnerClient(context)
    private val store=RunStore(context)
    private val owned=mutableListOf<String>()
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private suspend fun terminal(id:String):JSONObject=withTimeout(420000) {
        while(true){val r=JSONObject(client.service().snapshot(id));if(r.optString("state") in RamResults.terminalStates)return@withTimeout store.read(id)?:r;delay(80)}
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    @After fun cleanup()=runBlocking {
        owned.forEach { id->runCatching { client.service().cancel(id,"CURVE_TEST_CLEANUP");terminal(id);store.delete(id) } }
        client.close()
    }
    @Test fun combinedRunShowsFourRamScoresBeforeCurveAndExportsBoth()=runBlocking {
        lateinit var model:RamViewModel
        scenario.scenario.onActivity { model=ViewModelProvider(it)[RamViewModel::class.java] }
        withTimeout(15000){while(model.state.value.capabilities==null)delay(30)}
        scenario.scenario.onActivity { model.configure(RamConfig.matrixQuick().copy(curveMaxMiB=1,curveSteps=2));model.start() }
        withTimeout(15000){while(model.state.value.report==null){check(model.state.value.error==null){model.state.value.error!!};delay(30)}}
        val id=model.state.value.report!!.getString("run_id");owned+=id
        withTimeout(90000) { while(model.state.value.report?.optString("phase")!="CACHE_PROBING") {
            check(model.state.value.running) { model.state.value.report.toString() };delay(30)
        } }
        val early=store.read(id)!!
        assertEquals(4,early.getInt("completed_rounds"));assertEquals(4,early.getJSONArray("rounds").length())
        assertEquals("RAM",early.getJSONArray("stage_order").getString(0))
        assertEquals("cache_curve",early.getJSONArray("stage_order").getString(1))
        for(kind in MemoryPlanner.columns) {
            val expected="%.2f".format(Locale.US,RamResults.statistics(early,kind)!!.median)
            assertTrue(device.wait(Until.hasObject(By.res("curve_ram_$kind").text(expected)),5000))
        }
        val report=terminal(id)
        assertTrue(report.toString(),report.getString("state") in listOf("COMPLETED","PARTIAL"))
        assertEquals(4,report.getInt("total_rounds"));assertEquals(4,report.getInt("completed_rounds"))
        assertEquals(4,report.getJSONArray("cells").length())
        assertEquals(early.getJSONArray("rounds").toString(),report.getJSONArray("rounds").toString())
        val probe=report.getJSONObject("cache_probe");assertEquals(CacheProbe.METHOD,probe.getString("method"))
        val groups=probe.getJSONArray("groups");assertTrue(groups.length()>0)
        for(i in 0 until groups.length()) {
            val group=groups.getJSONObject(i);val samples=group.getJSONArray("samples")
            assertTrue(samples.length()>0)
            for(j in 0 until samples.length()) {
                val sample=samples.getJSONObject(j)
                assertTrue(sample.getInt("pass") in 0..1);assertTrue(sample.getInt("attempt") in 1..3)
                assertTrue(sample.getJSONObject("result").has("trials"))
            }
        }
        withTimeout(10000){while(model.state.value.running)delay(30)}
        val board=device.wait(Until.findObject(By.res("result_board")),5000).visibleBounds
        val chart=device.wait(Until.findObject(By.res("cache_latency_chart")),5000)
        assertTrue(board.contains(chart.visibleBounds));assertTrue(chart.visibleBounds.height()>90)
        assertFalse(device.hasObject(By.res("matrix_L1_0")))
        for(kind in MemoryPlanner.columns)assertTrue(board.contains(device.findObject(By.res("curve_ram_$kind")).visibleBounds))
        assertTrue(device.hasObject(By.res("curve_cpu_all")))
        device.findObject(By.res("curve_y_scale")).click()
        device.findObject(By.res("curve_references")).click()
        chart.click()
        assertNotNull(device.findObject(By.res("curve_selected_point")))
        assertEquals(probe.getInt("planned_points"),probe.getInt("completed_points"))
        assertFalse(probe.toString().contains("candidate_intervals"))
        val token=model.prepareExport(report)
        val pending=File(context.cacheDir,"pending_exports/$token.json")
        val exported=JSONObject(pending.readText())
        assertEquals(probe.toString(),exported.getJSONObject("cache_probe").toString())
        assertEquals(report.getJSONArray("rounds").toString(),exported.getJSONArray("rounds").toString())
        model.exportPrepared(null,token)
        withTimeout(5000){while(pending.exists())delay(20)}
        device.takeScreenshot(File(context.cacheDir,"curve-verification.png"))
        File(context.cacheDir,"curve-verification.json").writeText(report.toString())
        device.findObject(By.res("result_details")).click()
        for(kind in MemoryPlanner.columns) {
            val expected="%.2f".format(Locale.US,RamResults.statistics(report,kind)!!.median)
            assertTrue(device.wait(Until.hasObject(By.res("curve_ram_$kind").text(expected)),5000))
        }
        assertTrue(device.takeScreenshot(File(context.cacheDir,"ram-summary-details.png")))
    }
    @Test fun cancellingCurveStopsBeforeAnyRamScore()=runBlocking {
        val reply=JSONObject(client.service().startRam(RamConfig.matrixQuick().copy(curveIncludeRam=false).toJson().toString()))
        assertTrue(reply.toString(),reply.getBoolean("accepted"));val id=reply.getString("run_id");owned+=id
        withTimeout(15000){while(JSONObject(client.service().snapshot(id)).optString("phase")!="CACHE_PROBING")delay(20)}
        client.service().cancel(id,"CURVE_CANCEL_TEST")
        val report=terminal(id)
        assertEquals("CANCELLED",report.getString("state"));assertEquals(0,report.getJSONArray("rounds").length())
        assertTrue(CacheProbe.canResume(report))
        val request=report.getJSONObject("config").put("resume_run_id",id)
        val next=JSONObject(client.service().startRam(request.toString()))
        assertTrue(next.toString(),next.getBoolean("accepted"));val newId=next.getString("run_id");owned+=newId
        assertNotEquals(id,newId)
        client.service().cancel(newId,"RESUME_CANCEL_TEST")
        assertEquals(id,terminal(newId).getString("resumed_from_run_id"))
        assertEquals("CANCELLED",store.read(id)!!.getString("state"))
    }

    @Test fun workerDeathKeepsPointCheckpointAndAllowsExplicitResume()=runBlocking {
        val response=JSONObject(client.service().startRam(RamConfig.matrixQuick().copy(curveIncludeRam=false,curveMaxMiB=1,curveSteps=2).toJson().toString()))
        assertTrue(response.toString(),response.getBoolean("accepted"))
        val id=response.getString("run_id");owned+=id
        withTimeout(30000) { while((store.read(id)?.optJSONObject("cache_probe")?.optInt("completed_points")?:0)<2)delay(40) }
        val checkpoint=store.read(id)!!.getJSONObject("cache_probe").getInt("completed_points")
        val pid=response.getInt("worker_pid");assertNotEquals(android.os.Process.myPid(),pid)
        android.os.Process.killProcess(pid)
        withTimeout(30000) { while(store.read(id)?.optString("state")!="INTERRUPTED")delay(50) }
        val recovered=store.read(id)!!
        assertTrue(recovered.getJSONObject("cache_probe").getInt("completed_points")>=checkpoint)
        assertTrue(CacheProbe.canResume(recovered))
        assertTrue(recovered.getString("error").startsWith("PROCESS_DIED"))
        val request=JSONObject(recovered.getJSONObject("config").toString()).put("resume_run_id",id)
        val next=JSONObject(client.service().startRam(request.toString()))
        assertTrue(next.toString(),next.getBoolean("accepted"));val nextId=next.getString("run_id");owned+=nextId
        assertNotEquals(pid,next.getInt("worker_pid"))
        withTimeout(15000) { while((store.read(nextId)?.optJSONObject("cache_probe")?.optInt("completed_points")?:0)<=checkpoint)delay(40) }
        client.service().cancel(nextId,"RECOVERY_TEST_DONE")
        val final=terminal(nextId)
        assertEquals(id,final.getString("resumed_from_run_id"))
        assertTrue(final.getJSONObject("cache_probe").getInt("completed_points")>checkpoint)
        assertEquals("INTERRUPTED",store.read(id)!!.getString("state"))
        File(context.cacheDir,"curve-recovery-verification.json").writeText(recovered.toString())
    }

    @Test fun curveOnlyHistoryExplicitlyKeepsRamUnmeasured()=runBlocking {
        lateinit var model:RamViewModel
        scenario.scenario.onActivity { model=ViewModelProvider(it)[RamViewModel::class.java] }
        withTimeout(15000){while(model.state.value.capabilities==null)delay(30)}
        val response=JSONObject(client.service().startRam(RamConfig.matrixQuick().copy(curveIncludeRam=false).toJson().toString()))
        assertTrue(response.toString(),response.getBoolean("accepted"));val id=response.getString("run_id");owned+=id
        withTimeout(15000){while(JSONObject(client.service().snapshot(id)).optString("phase")!="CACHE_PROBING")delay(20)}
        client.service().cancel(id,"HISTORY_TEST");val report=terminal(id)
        val original=store.read(id)!!.toString()
        scenario.scenario.onActivity { model.selectHistory(report) }
        device.wait(Until.findObject(By.res("tab_2")),5000).click()
        assertTrue(device.wait(Until.hasObject(By.res("ram_summary_title").text("RAM · 本次未测试")),5000))
        for(kind in MemoryPlanner.columns) {
            assertEquals("—",device.findObject(By.res("curve_ram_$kind")).text)
            assertEquals("未测",device.findObject(By.res("curve_ram_hint_$kind")).text)
        }
        device.findObject(By.res("result_details")).click()
        assertTrue(device.wait(Until.hasObject(By.res("ram_summary_title").text("RAM · 本次未测试")),5000))
        assertEquals(original,store.read(id)!!.toString())
    }
}
