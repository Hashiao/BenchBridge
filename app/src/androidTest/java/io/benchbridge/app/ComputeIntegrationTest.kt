package io.benchbridge.app

import android.content.Context
import android.os.SystemClock
import android.graphics.BitmapFactory
import java.io.File
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.benchbridge.app.compute.*
import io.benchbridge.app.ram.*
import io.benchbridge.app.storage.StorageConfig
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
class ComputeIntegrationTest {
    @get:Rule val scenario=ActivityScenarioRule(MainActivity::class.java)
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val client=RunnerClient(context)
    private val store=RunStore(context,"compute_results")
    private val owned=mutableListOf<String>()
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun cpus()=JSONObject(ComputeNative.cpuCapabilities()).getJSONArray("cpu_ids").let { a -> (0 until minOf(a.length(),2)).map { a.getInt(it) }.toIntArray() }
    private suspend fun start(config:ComputeConfig):String {
        val reply=JSONObject(client.service().startCompute(config.toJson().toString()))
        assertTrue(reply.toString(),reply.getBoolean("accepted"));return reply.getString("run_id").also { owned+=it }
    }
    private suspend fun terminal(id:String):JSONObject=withTimeout(180000){
        while(true){val report=JSONObject(client.service().snapshot(id));if(report.optString("state") in RamResults.terminalStates)return@withTimeout store.read(id)?:report;delay(50)}
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    @After fun cleanup()=runBlocking{
        owned.forEach { id -> runCatching{client.service().cancel(id,"TEST_CLEANUP");terminal(id)};store.delete(id) }
        client.close();device.wakeUp();device.pressMenu();Unit
    }
    @Test fun cpuArithmeticCryptoAndFractalsVerifyTheirWork(){
        assertTrue("AES-256 and SHA-1 known-answer tests",ComputeNative.selfTest())
        val h=ComputeNative.createSession(context.assets)
        try{
            for(kind in 3..11){
                val result=JSONObject(ComputeNative.runCpu(h,kind,cpus(),0,80,128,128,4))
                assertEquals(result.toString(),"COMPLETED",result.getString("status"))
                assertTrue(result.getBoolean("verified"));assertTrue(result.getLong("elapsed_ns")>=80000000)
                assertTrue(ComputeResults.value(result).isFinite()&&ComputeResults.value(result)>0)
                assertEquals(ComputeKind.entries[kind].unit,result.getString("unit"))
                assertEquals(result.getJSONArray("cpu_ids").toString(),result.getJSONArray("observed_cpus").toString())
                val units=when(kind){8,9->65536;10,11->128*128;4,7->32;else->64}
                val iters=if(kind>=8)1 else result.getLong("iterations_per_batch")
                assertEquals(result.getLong("batches")*units*iters,result.getLong("work_units"))
                assertEquals("gpgpu-v2",result.getString("protocol"))
                if(kind==8||kind==9){
                    assertEquals(4*1048576L,result.getLong("working_set_bytes"))
                    assertEquals(65536,result.getInt("message_bytes"))
                    assertEquals(result.getLong("messages_processed")*65536,result.getLong("work_units"))
                }
            }
        }finally{ComputeNative.release(h)}
    }
    @Test fun gpuComputeShadersVerifyAgainstCpuReferences(){
        // 软件 Vulkan 仅允许在内核验证中使用，正常界面不改变选择策略。
        // Permit software Vulkan only for kernel verification, without changing the normal UI selection policy.
        val caps=JSONObject(ComputeNative.gpuCapabilities(true));assertTrue(caps.toString(),caps.getBoolean("supported"))
        val h=ComputeNative.createSession(context.assets,true)
        try{
            for(kind in 0..11){
                val result=JSONObject(ComputeNative.runGpu(h,kind,4,0,60,128,128))
                if(kind in listOf(4,11)&&!caps.optBoolean("fp64")||kind==7&&!caps.optBoolean("int64")){
                    assertEquals("UNSUPPORTED",result.getString("status"));continue
                }
                assertEquals(result.toString(),"COMPLETED",result.getString("status"))
                assertTrue(result.getBoolean("verified"));assertTrue(ComputeResults.value(result)>0)
                assertEquals(ComputeKind.entries[kind].unit,result.getString("unit"))
                val batches=result.getLong("batches")
                val expected=if(kind<=2)batches*4*1048576
                    else if(kind>=10)batches*128*128 else result.getLong("completed_invocations")*result.getLong("iterations_per_invocation")*(if(kind==8)16 else if(kind==9)65536 else if(kind==4||kind==7)32 else 64)
                assertEquals(expected,result.getLong("work_units"))
                assertEquals("measurement-window",result.getString("timer_scope"))
                assertEquals(0,result.getInt("measured_transfer_commands"))
                assertTrue(result.getLong("elapsed_ns")>=60000000L)
                assertTrue(result.getLong("device_elapsed_ns")>0)
                assertTrue(result.getLong("device_elapsed_ns")<=result.getLong("elapsed_ns"))
                assertTrue(result.getInt("output_memory_flags") and 1 != 0)
                if(kind==2||kind==8)assertEquals(4*1048576L,result.getLong("input_bytes")+result.getLong("output_bytes"))
                if(kind==8||kind==9){
                    assertEquals(65536,result.getInt("message_bytes"))
                    assertEquals(result.getLong("messages_processed")*65536,result.getLong("work_units"))
                }
            }
            val resized=JSONObject(ComputeNative.runGpu(h,10,4,0,50,64,64))
            assertEquals("COMPLETED",resized.getString("status"));assertEquals(4096,resized.getInt("invocations"))
        }finally{ComputeNative.release(h)}
    }
    @Test fun legacyFractalResultsConvertUsingSavedDimensionsWithoutRewritingHistory(){
        for(size in listOf(256,512,1024)){
            val old=JSONObject().put("kind",10).put("target","cpu").put("status","COMPLETED").put("verified",true)
                .put("unit","FPS").put("work_units",10).put("elapsed_ns",2000000000L).put("width",size).put("height",size)
            val original=old.toString()
            val current=JSONObject(old.toString()).put("unit","MPix/s").put("work_units",10L*size*size)
            assertEquals(5.0*size*size/1e6,ComputeResults.value(old),1e-9)
            assertEquals(ComputeResults.value(current),ComputeResults.value(old),1e-9)
            assertEquals(original,old.toString())
            old.remove("width");assertTrue(ComputeResults.value(old).isNaN())
        }
        val cpu=ComputeConfig(kinds=listOf(9),targets=listOf("cpu"),memoryMiB=256)
        assertTrue(cpu.estimatedBytes>256L*1048576)
        val gpu=cpu.copy(targets=listOf("gpu"));assertTrue(gpu.estimatedBytes>512L*1048576)
    }
    @Test fun bulkCryptoUsesConfiguredWorksetAndCancelsDuringMeasurement(){
        val h=ComputeNative.createSession(context.assets,true)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            for(kind in listOf(8,9)){
                val cpu=JSONObject(ComputeNative.runCpu(h,kind,cpus(),10,70,64,64,5))
                val gpu=JSONObject(ComputeNative.runGpu(h,kind,5,10,70,64,64))
                assertEquals(cpu.toString(),"COMPLETED",cpu.getString("status"))
                assertEquals(gpu.toString(),"COMPLETED",gpu.getString("status"))
                for(field in listOf("message_bytes","working_set_bytes","input_bytes","output_bytes"))assertEquals(field,cpu.getLong(field),gpu.getLong(field))
                assertEquals(5*1048576L,gpu.getLong("working_set_bytes"))
                assertTrue(cpu.getBoolean("input_prepared_before_timing"));assertTrue(gpu.getBoolean("input_prepared_before_timing"))
            }
            val task=pool.submit<String>{ComputeNative.runGpu(h,9,5,0,5000,64,64)}
            Thread.sleep(200);val start=SystemClock.elapsedRealtime();ComputeNative.cancel(h)
            val result=JSONObject(task.get(8,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(result.toString(),"INTERRUPTED",result.getString("status"))
            assertFalse(result.optBoolean("verified"));assertTrue(SystemClock.elapsedRealtime()-start<8000)
        }finally{ComputeNative.cancel(h);pool.shutdownNow();ComputeNative.release(h)}
    }
    @Test fun cancellingComputeRejectsRamRomOverlapAndReleasesResources()=runBlocking{
        val id=start(ComputeConfig.quick().copy(targets=listOf("cpu"),kinds=listOf(3),durationMs=5000))
        withTimeout(20000){while(JSONObject(client.service().snapshot(id)).optString("phase")!="MEASURING")delay(20)}
        assertFalse(JSONObject(client.service().startRam(RamConfig.quick().toJson().toString())).getBoolean("accepted"))
        assertFalse(JSONObject(client.service().startStorage(StorageConfig.quick().toJson().toString())).getBoolean("accepted"))
        assertFalse(JSONObject(client.service().startCompute(ComputeConfig.quick().toJson().toString())).getBoolean("accepted"))
        val start=SystemClock.elapsedRealtime();client.service().cancel(id,"USER_TEST_STOP")
        val report=terminal(id);assertEquals("CANCELLED",report.getString("state"));assertTrue(SystemClock.elapsedRealtime()-start<3000)
        assertEquals(0,report.getInt("completed_rounds"));assertFalse(JSONObject(client.service().capabilities()).getBoolean("wake_lock_held"))
    }
    @Test fun coordinatorCompletesSupportedCellsWhileScreenSleeps()=runBlocking{
        val id=start(ComputeConfig.quick().copy(targets=listOf("cpu"),kinds=listOf(0,1,2,3,8,9),durationMs=250))
        device.sleep()
        val report=terminal(id)
        assertEquals(report.toString(),"COMPLETED",report.getString("state"));assertEquals(6,report.getInt("completed_rounds"))
        assertEquals(6,report.getJSONArray("rounds").length())
        assertTrue((0 until 6).any { !report.getJSONArray("rounds").getJSONObject(it).getBoolean("screen_interactive_after") })
        assertFalse(JSONObject(client.service().capabilities()).getBoolean("foreground_run"))
    }
    @Test fun newTabShowsAllRowsWithoutChangingRamOrRomAndKeepsFrozenHistory()=runBlocking{
        lateinit var model:RamViewModel
        scenario.scenario.onActivity{model=ViewModelProvider(it)[RamViewModel::class.java]}
        withTimeout(15000){while(model.state.value.capabilities==null)delay(20)}
        val ram=model.state.value.config;val rom=model.state.value.storageConfig
        device.wait(Until.findObject(By.res("tab_4")),5000).click()
        assertFalse(device.hasObject(By.res("compute_default")))
        assertNotNull(device.findObject(By.res("compute_start")))
        val board=device.wait(Until.findObject(By.res("compute_board")),5000).visibleBounds
        for(kind in 0..11)assertTrue(board.contains(device.findObject(By.res("compute_row_$kind")).visibleBounds))
        scenario.scenario.onActivity{model.configureCompute(ComputeConfig.quick().copy(targets=listOf("cpu"),kinds=listOf(3,8,9)));model.startCompute()}
        withTimeout(15000){while(model.state.value.computeReport==null)delay(20)}
        val id=model.state.value.computeReport!!.getString("run_id");owned+=id
        val report=terminal(id);assertEquals("COMPLETED",report.getString("state"))
        withTimeout(15000){while(model.state.value.running)delay(20)}
        val saved=store.read(id)!!.toString()
        scenario.scenario.onActivity{model.configureCompute(ComputeConfig())}
        assertNull(model.state.value.computeReport);assertEquals(saved,store.read(id)!!.toString())
        assertEquals(ram,model.state.value.config);assertEquals(rom,model.state.value.storageConfig)
    }

    @Test fun fullQuickRunSharesAndExportsEverySupportedCell()=runBlocking{
        lateinit var model:RamViewModel
        scenario.scenario.onActivity{model=ViewModelProvider(it)[RamViewModel::class.java]}
        device.wait(Until.findObject(By.res("tab_4")),5000).click()
        device.wait(Until.findObject(By.res("compute_settings")),5000).click()
        device.wait(Until.findObject(By.res("compute_quick")),5000).click()
        device.findObject(By.res("settings_done")).click()
        device.wait(Until.findObject(By.res("compute_start")),5000).click()
        withTimeout(15000){while(model.state.value.computeReport==null){check(model.state.value.error==null){model.state.value.error!!};delay(20)}}
        val id=model.state.value.computeReport!!.getString("run_id");owned+=id
        val report=terminal(id)
        assertTrue(report.toString(),report.getString("state") in setOf("COMPLETED","PARTIAL"))
        assertEquals(24,report.getInt("processed_rounds"))
        val cells=ComputeResults.cells(report);assertEquals(24,cells.size)
        val count=cells.count { it.getString("state")=="COMPLETED" }
        assertEquals(count,report.getInt("completed_rounds"));assertTrue(count>=12)
        withTimeout(15000){while(model.state.value.running)delay(30)}
        val board=device.wait(Until.findObject(By.res("compute_board")),5000).visibleBounds
        for(kind in 0..11)for(target in listOf("cpu","gpu")){
            val cell=device.findObject(By.res("compute_${target}_$kind"))
            assertTrue(board.contains(cell.visibleBounds))
            if(ComputeResults.median(report,kind,target)!=null)assertNotEquals("—",cell.text)
        }
        val token=model.prepareExport(report)
        val pending=File(context.cacheDir,"pending_exports/$token.json")
        val exported=JSONObject(pending.readText())
        assertEquals(report.getJSONArray("rounds").toString(),exported.getJSONArray("rounds").toString())
        model.exportPrepared(null,token)
        withTimeout(5000){while(pending.exists())delay(20)}
        val folder=File(context.cacheDir,"shared_screenshots");val before=folder.listFiles()?.toSet().orEmpty()
        device.findObject(By.res("share_screenshot")).click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.intentresolver")),10000))
        val file=folder.listFiles()!!.single { it !in before }
        try{val bitmap=BitmapFactory.decodeFile(file.absolutePath);assertEquals(device.displayWidth,bitmap.width);assertTrue(bitmap.height>=device.displayHeight*0.9);bitmap.recycle()}
        finally{device.pressBack();file.delete()}
    }

    @Test fun gpuCancellationDrainsItsBatchAndReleasesTheSession(){
        val h=ComputeNative.createSession(context.assets,true)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try{
            val task=pool.submit<String>{ComputeNative.runGpu(h,3,4,1000,5000,128,128)}
            Thread.sleep(100);val start=SystemClock.elapsedRealtime();ComputeNative.cancel(h)
            val result=JSONObject(task.get(10,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(result.toString(),"INTERRUPTED",result.getString("status"))
            assertFalse(result.optBoolean("verified"));assertTrue(SystemClock.elapsedRealtime()-start<8000)
        }finally{ComputeNative.cancel(h);pool.shutdownNow();ComputeNative.release(h)}
        val next=ComputeNative.createSession(context.assets);ComputeNative.release(next)
    }
}
