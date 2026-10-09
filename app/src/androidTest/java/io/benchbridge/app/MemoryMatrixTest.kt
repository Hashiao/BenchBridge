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
import androidx.test.uiautomator.Until
import io.benchbridge.app.hardware.*
import io.benchbridge.app.ram.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryMatrixTest {
    @get:Rule val scenario = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val client = RunnerClient(context)
    private val owned = mutableListOf<String>()
    private val store = RunStore(context)

    @After fun cleanup() = runBlocking {
        owned.forEach { id ->
            client.service().cancel(id, "MATRIX_TEST_CLEANUP")
            terminal(id)
            store.delete(id)
        }
        client.close()
    }
    private suspend fun terminal(id: String): JSONObject = withTimeout(240000) {
        var report: JSONObject
        do {
            report = JSONObject(client.service().snapshot(id))
            if (report.optString("state") in RamResults.terminalStates) break
            delay(100)
        } while (true)
        store.read(id) ?: report
    }
    private fun cpu(id: Int) = CpuCore(id, "$id", 1024, 3000000, "", "0,1", true)
    private fun fixture() = CpuTopology(listOf(cpu(0), cpu(1)), listOf(
        CpuCache("L1:0",1,65536,64,listOf(0),"test"), CpuCache("L1:1",1,65536,64,listOf(1),"test"),
        CpuCache("L2:0,1",2,1048576,64,listOf(0,1),"test"),
        CpuCache("L3:0,1",3,8388608,64,listOf(0,1),"test")), null,"test")

    @Test fun sharedDomainsConstrainFootprintsAndRamExceedsLastLevel() {
        val topology = fixture()
        val config = RamConfig.matrixQuick().copy(cacheCurve=false, expandRamWorkingSet=true)
        val budget = 1024L * 1048576
        val l2 = MemoryPlanner.plan(topology,config,"L2",2,listOf(0,1),budget)!!
        assertEquals(786432L,l2.bytes)
        assertEquals(listOf(393216L,393216L),l2.workingSets)
        val ram = MemoryPlanner.plan(topology,config.copy(workingSetMiB=1),"RAM",0,listOf(0,1),budget)!!
        assertEquals(16777216L,ram.bytes)
        assertNull(MemoryPlanner.plan(topology,config,"L2",0,listOf(0,1),1024))
        assertNull(MemoryPlanner.plan(topology,config,"L2",5,listOf(0,1),budget))
        assertNull(MemoryPlanner.plan(topology.copy(caches=emptyList()),config,"L3",0,listOf(0),budget))
        assertNull(MemoryPlanner.plan(topology,config,"L1",0,listOf(0,0),budget))
    }

    @Test fun fixedDefaultsPreserve64MiBAcrossCorePlansAndLegacyRemainsReadable() {
        val config=RamConfig.aida64(3)
        assertEquals(64,config.workingSetMiB);assertEquals(64,config.latencySetMiB);assertEquals(64,config.curveMaxMiB)
        val topology=fixture().copy(cores=(0..2).map(::cpu),caches=listOf(CpuCache("huge",3,128L*1048576,64,listOf(0,1,2),"test")))
        for(kind in MemoryPlanner.columns) {
            val plan=MemoryPlanner.plan(topology,config,"RAM",kind,if(kind==5)listOf(0)else listOf(0,1,2),1024L*1048576)!!
            assertEquals(64L*1048576,plan.bytes)
        }
        assertEquals(config,RamConfig.fromJson(config.toJson().toString()))
        val old=config.toJson().apply{remove("expand_ram_working_set");put("working_set_mib",512);put("latency_set_mib",256)}
        val legacy=RamConfig.fromJson(old.toString());assertTrue(legacy.expandRamWorkingSet);assertEquals(512,legacy.workingSetMiB)
    }

    @Test fun catalogRequiresUnambiguousIdentityAndMatchingTopology() {
        val catalog = SocCatalog(context)
        assertTrue(catalog.entries().size >= 15)
        val soc = catalog.match(listOf("Qualcomm SM8650-AB"))!!
        assertEquals("sm8650",soc.getString("id"))
        assertNull(catalog.match(listOf("SM86500")))
        assertNull(catalog.match(listOf("SM8650","SM8750")))
        assertEquals(7,catalog.match(listOf("SM8750-3-AB"))!!.getInt("cpu_count"))
        assertTrue(catalog.entries().all { it.getJSONObject("gpu").isNull("alus") })
        assertTrue(CpuTopology.withCatalog(fixture().copy(soc=soc)).notes.contains("CATALOG_CORE_COUNT_MISMATCH"))
        val cores = (0..7).map { cpu(it).copy(maxKhz=if(it==7)3300000 else if(it>=2)3000000 else 2000000) }
        val actual = CpuCache("L1:0",1,32768,64,listOf(0),"runtime-sysfs")
        val result = CpuTopology.withCatalog(CpuTopology(cores,listOf(actual),soc,catalog.revision))
        assertEquals(32768L,result.cache(0,1)!!.bytes)
        assertEquals(listOf(0,1),result.cache(0,2)!!.cpus)
        assertEquals(12582912L,result.cache(7,3)!!.bytes)
        assertEquals(listOf(0,1,2,4),CpuTopology.cpuList("0-2,4"))
        assertEquals(49152L,CpuTopology.cacheBytes("48K"))
    }

    @Test fun pinnedSmallKernelsCountEveryPassAndVerifySelectedCpu() {
        val ids = JSONObject(RamNative.capabilities()).getJSONArray("allowed_cpu_ids")
        assertTrue(ids.length()>0)
        val cpu = ids.getInt(0)
        val handle = RamNative.createSession()
        try {
            for (kind in MemoryPlanner.columns) {
                val sample = JSONObject(RamNative.runPinnedRound(handle,kind,longArrayOf(16384),intArrayOf(cpu),25,100,99,64))
                assertEquals(sample.toString(),"COMPLETED",sample.getString("status"))
                assertTrue(sample.getBoolean("verified"))
                assertEquals(cpu,sample.getJSONArray("observed_start_cpus").getInt(0))
                assertEquals(cpu,sample.getJSONArray("observed_end_cpus").getInt(0))
                assertEquals(16384L,sample.getJSONArray("per_thread_working_set_bytes").getLong(0))
                assertEquals(sample.getLong("operations")*8*(if(kind==2)2 else 1),sample.getLong("logical_bytes"))
                assertTrue(sample.getLong("operations")>16384/8)
                if(kind==5) assertEquals(64,sample.getInt("node_stride_bytes"))
            }
            val invalid = JSONObject(RamNative.runPinnedRound(handle,0,longArrayOf(16384),intArrayOf(1023),0,50,1,64))
            assertEquals("AFFINITY_CPU_NOT_ALLOWED",invalid.getString("error"))
            val valid = JSONObject(RamNative.runPinnedRound(handle,0,longArrayOf(16384),intArrayOf(cpu),0,50,1,64))
            assertEquals("COMPLETED",valid.getString("status"))
        } finally { RamNative.releaseSession(handle) }
    }

    @Test fun cancellingPinnedSetupReleasesEveryWorkerBarrier() {
        val ids = JSONObject(RamNative.capabilities()).getJSONArray("allowed_cpu_ids")
        val handle = RamNative.createSession()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit<String> { RamNative.runPinnedRound(handle,5,longArrayOf(256L*1048576),
                intArrayOf(ids.getInt(0)),1000,10000,99,64) }
            val deadline = SystemClock.elapsedRealtime()+5000
            while(RamNative.phase(handle)!=1 && !task.isDone && SystemClock.elapsedRealtime()<deadline) Thread.sleep(1)
            assertEquals(1,RamNative.phase(handle))
            RamNative.cancelSession(handle)
            val result=JSONObject(task.get(3,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("INTERRUPTED",result.getString("status"))
        } finally { RamNative.cancelSession(handle); pool.shutdownNow(); RamNative.releaseSession(handle) }
        val next=RamNative.createSession()
        RamNative.releaseSession(next)
    }

    @Test fun calibrationAndSixteenScoresPersistSeparatelyAndFitOneScreen() = runBlocking {
        lateinit var model: RamViewModel
        scenario.scenario.onActivity { model=ViewModelProvider(it)[RamViewModel::class.java] }
        withTimeout(15000) { while(model.state.value.capabilities==null) delay(30) }
        scenario.scenario.onActivity { model.configure(RamConfig.matrixQuick().copy(cacheCurve=false));model.start() }
        withTimeout(15000) { while(model.state.value.report==null) { check(model.state.value.error==null) { model.state.value.error!! };delay(30) } }
        val id=model.state.value.report!!.getString("run_id");owned+=id
        val report=terminal(id)
        assertTrue(report.toString(),report.getString("state") in setOf("COMPLETED","PARTIAL"))
        assertEquals(16,report.getJSONArray("cells").length())
        val measured=report.getJSONArray("effective_plan").length()
        assertTrue(measured>=4)
        assertEquals(measured,report.getInt("completed_rounds"))
        assertEquals(measured,report.getJSONArray("rounds").length())
        for(level in MemoryPlanner.levels) for(kind in MemoryPlanner.columns) {
            val cell=RamResults.cell(report,level,kind)!!
            if(cell.optString("state")=="UNSUPPORTED") {
                assertTrue(cell.getString("reason").isNotBlank())
                assertTrue(RamResults.validRounds(report,kind,level).isEmpty())
                continue
            }
            val plan=cell.getJSONObject("plan")
            val trials=cell.getJSONObject("calibration").getJSONArray("candidates")
            assertTrue(trials.length()>0)
            val sample=RamResults.validRounds(report,kind,level).single()
            assertEquals(plan.getLong("working_set_bytes"),sample.getLong("working_set_bytes"))
            assertEquals(plan.getJSONArray("cpu_ids").toString(),sample.getJSONArray("requested_cpus").toString())
            if(kind==5) assertEquals(1,plan.getInt("threads"))
        }
        withTimeout(15000) { while(model.state.value.running) delay(50) }
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val board=device.wait(Until.findObject(By.res("result_board")),5000).visibleBounds
        for(level in MemoryPlanner.levels) for(kind in MemoryPlanner.columns) {
            val cell=device.wait(Until.findObject(By.res("matrix_${level}_$kind")),5000)
            assertNotNull(cell)
            assertTrue("Every score must remain inside the screenshot",board.contains(cell.visibleBounds))
            if(RamResults.cell(report,level,kind)!!.optString("state")=="COMPLETED") {
                assertNotEquals("—",cell.text)
                val hint=device.findObject(By.res("matrix_plan_${level}_$kind"))
                assertTrue(hint.text.isNotBlank())
                assertTrue(board.contains(hint.visibleBounds))
                assertTrue(hint.visibleBounds.height()>0)
            }
        }
        assertTrue(device.findObject(By.res("ram_start")).visibleBounds.bottom < device.displayHeight)
    }

    @Test fun cancellationDuringCalibrationStopsWithoutPublishingTrialScores() = runBlocking {
        val reply=JSONObject(client.service().startRam(RamConfig.matrixQuick().copy(cacheCurve=false).copy(calibrationMs=1000).toJson().toString()))
        assertTrue(reply.toString(),reply.getBoolean("accepted"))
        val id=reply.getString("run_id");owned+=id
        withTimeout(15000) { while(JSONObject(client.service().snapshot(id)).optString("phase")!="CALIBRATING") delay(20) }
        val start=SystemClock.elapsedRealtime()
        client.service().cancel(id,"MATRIX_CANCEL_TEST")
        val report=terminal(id)
        assertEquals("CANCELLED",report.getString("state"))
        assertTrue(SystemClock.elapsedRealtime()-start<3000)
        assertEquals(0,report.getJSONArray("rounds").length())
        assertEquals(0,report.getInt("completed_rounds"))
    }
}
