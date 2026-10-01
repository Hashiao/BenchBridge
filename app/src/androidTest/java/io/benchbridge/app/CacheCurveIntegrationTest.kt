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
    @Test fun curveReplacesCacheRowsPreservesRamAndExportsEveryTrial()=runBlocking {
        lateinit var model:RamViewModel
        scenario.scenario.onActivity { model=ViewModelProvider(it)[RamViewModel::class.java] }
        withTimeout(15000){while(model.state.value.capabilities==null)delay(30)}
        scenario.scenario.onActivity { model.configure(RamConfig.matrixQuick());model.start() }
        withTimeout(15000){while(model.state.value.report==null){check(model.state.value.error==null){model.state.value.error!!};delay(30)}}
        val id=model.state.value.report!!.getString("run_id");owned+=id
        val report=terminal(id)
        assertTrue(report.toString(),report.getString("state") in listOf("COMPLETED","PARTIAL"))
        assertEquals(4,report.getInt("total_rounds"));assertEquals(4,report.getInt("completed_rounds"))
        assertEquals(4,report.getJSONArray("cells").length())
        val probe=report.getJSONObject("cache_probe");assertEquals(CacheProbe.METHOD,probe.getString("method"))
        val groups=probe.getJSONArray("groups");assertTrue(groups.length()>0)
        for(i in 0 until groups.length()) {
            val group=groups.getJSONObject(i);val samples=group.getJSONArray("samples")
            assertTrue(samples.length()>0)
            for(j in 0 until samples.length()) {
                val sample=samples.getJSONObject(j);assertEquals(5,sample.getInt("kind"))
                assertEquals(sample.isNull("quality_reason"),sample.getBoolean("accepted"))
                if(sample.getBoolean("accepted"))assertNull(CacheProbe.qualityReason(sample.getJSONObject("result")))
            }
        }
        withTimeout(10000){while(model.state.value.running)delay(30)}
        val board=device.wait(Until.findObject(By.res("result_board")),5000).visibleBounds
        val chart=device.wait(Until.findObject(By.res("cache_latency_chart")),5000)
        assertTrue(board.contains(chart.visibleBounds));assertTrue(chart.visibleBounds.height()>90)
        assertFalse(device.hasObject(By.res("matrix_L1_0")))
        for(kind in MemoryPlanner.columns)assertTrue(board.contains(device.findObject(By.res("curve_ram_$kind")).visibleBounds))
        device.findObject(By.res("curve_y_scale")).click()
        device.findObject(By.res("curve_references")).click()
        chart.click()
        assertTrue(device.findObject(By.res("curve_selected_point")).text.contains("ns"))
        val token=model.prepareExport(report)
        val pending=File(context.cacheDir,"pending_exports/$token.json")
        val exported=JSONObject(pending.readText())
        assertEquals(probe.toString(),exported.getJSONObject("cache_probe").toString())
        model.exportPrepared(null,token)
        withTimeout(5000){while(pending.exists())delay(20)}
        device.takeScreenshot(File(context.cacheDir,"curve-verification.png"))
        File(context.cacheDir,"curve-verification.json").writeText(report.toString())
    }
    @Test fun cancellingCurveStopsBeforeAnyRamScore()=runBlocking {
        val reply=JSONObject(client.service().startRam(RamConfig.matrixQuick().toJson().toString()))
        assertTrue(reply.toString(),reply.getBoolean("accepted"));val id=reply.getString("run_id");owned+=id
        withTimeout(15000){while(JSONObject(client.service().snapshot(id)).optString("phase")!="CACHE_PROBING")delay(20)}
        client.service().cancel(id,"CURVE_CANCEL_TEST")
        val report=terminal(id)
        assertEquals("CANCELLED",report.getString("state"));assertEquals(0,report.getJSONArray("rounds").length())
    }
}
